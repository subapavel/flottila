package io.flottila.sim;

import io.flottila.api.Step;
import io.flottila.core.flow.Intake;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.flottila.sim.World.Unit;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.flottila.sim.SimRuns.PER_PUSH;
import static io.flottila.sim.SimRuns.TICKS;
import static io.flottila.sim.SimRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-101 scheduler: at most one message thread is runnable at a time, held at boundaries the simulation owns — the claim cap
 * of POLL and the WAKE of a Retry backoff among them.
 */
class SimSchedulerTest {

    @Test
    void atMostOneRunIsRunnable() {
        for (SimSeed seed : PER_PUSH.subList(0, 4)) {
            Probe probe = new Probe();
            Verdict v = run(seed, SimConfig.V1, TICKS, probe);
            assertTrue(v.passed(), v::report);
            assertEquals(List.of(), List.copyOf(probe.violations), () -> "seed " + seed + ": more than one run was runnable");
            assertEquals(1, probe.peak.get(), () -> "seed " + seed + ": " + probe.peak + " runs were at a sim call at once");
            assertTrue(probe.entries.get() > 50, () -> "seed " + seed + ": runs made only " + probe.entries + " sim calls");
            assertTrue(probe.distinct.get() > 1, () -> "seed " + seed + ": one message thread only, nothing could overlap");
            assertTrue(probe.afterRelease.get() > 0, () -> "seed " + seed + ": no run made a sim call after its RELEASE; the resume went unprobed");
            assertTrue(probe.afterWake.get() > 0, () -> "seed " + seed + ": no run made a sim call after its WAKE; the resume went unprobed");
            assertFalse(probe.platform.get(), () -> "seed " + seed + ": a message ran on a platform thread");
        }
    }

    @Test
    void aClaimFromARunThreadAnswersNothing() {
        for (SimSeed seed : PER_PUSH.subList(0, 4)) {
            Claims claims = new Claims();
            Verdict v = run(seed, SimConfig.V1, TICKS, claims);
            assertTrue(v.passed(), v::report);

            List<Claims.Call> seen = List.copyOf(claims.claims);
            assertFalse(seen.isEmpty(), () -> "seed " + seed + ": no claim reached the queue");
            Set<Thread> claimers = seen.stream().map(Claims.Call::thread).collect(Collectors.toSet());
            assertEquals(1, claimers.size(), () -> "seed " + seed + ": claims reached the queue from " + claimers + ", not the driver alone");
            Thread driver = claimers.iterator().next();
            assertFalse(claims.runThreads.contains(driver), () -> "seed " + seed + ": the claiming thread " + driver + " ran a message");
            assertTrue(seen.stream().allMatch(c -> c.max() == 1), () -> "seed " + seed + ": a claim above the cap of 1 reached the queue: " + seen);
            assertTrue(seen.stream().anyMatch(c -> c.answered() == 1), () -> "seed " + seed + ": no claim reaching the queue took an item");
            // the witness that a run thread did poll: a chain ended off the driver, and FlowWorker polls once at each chain end
            assertTrue(claims.chainEndsOff(driver) > 0, () -> "seed " + seed + ": no chain ended off the driver; the chain-end poll was never made");
        }
    }

    @Test
    void aRetryBackoffIsAWakeUnit() {
        SimSeed seed = new SimSeed(1);
        Units refused = new Units();
        Verdict v = run(seed, SimConfig.V1.withRate(FaultKind.REFUSE, 1.0), 200, refused);
        assertTrue(v.passed(), v::report);
        List<Unit> units = List.copyOf(refused.units);
        List<Integer> wakes = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) if (units.get(i).kind() == Unit.Kind.WAKE) wakes.add(i);
        assertFalse(wakes.isEmpty(), "every partner call refused, yet no backoff came back as a WAKE unit");
        for (int w : wakes) {
            Unit wake = units.get(w);
            assertTrue(wake.messageId().isPresent(), () -> "a WAKE names no message: " + wake);
            int previous = -1;   // the message's unit before this wake: its START, its POLL, its RELEASE or its previous WAKE
            for (int i = w - 1; i >= 0 && previous < 0; i--) if (units.get(i).messageId().equals(wake.messageId())) previous = i;
            assertTrue(previous >= 0, () -> wake + " wakes a message no earlier unit ran");
            boolean advanced = false;
            for (int i = previous + 1; i < w; i++) advanced |= units.get(i).kind() == Unit.Kind.ADVANCE;
            int from = previous;
            assertTrue(advanced, () -> wake + " came without an ADVANCE after " + units.get(from) + ": the backoff was not due on the fake clock");
        }

        // without a refusal nothing backs off: the other timers (leases, deadlines) fire under ADVANCE, never as a WAKE
        Units calm = new Units();
        Verdict quiet = run(seed, SimConfig.V1.withRate(FaultKind.REFUSE, 0.0).withRate(FaultKind.DELAY, 0.0).withRate(FaultKind.STORE_REFUSE, 0.0), 200, calm);
        assertTrue(quiet.passed(), quiet::report);
        assertTrue(calm.units.stream().anyMatch(u -> u.kind() == Unit.Kind.ADVANCE), "no ADVANCE in 200 ticks: the timers never ran");
        assertEquals(List.of(), calm.units.stream().filter(u -> u.kind() == Unit.Kind.WAKE).toList(), "a WAKE without a Retry backoff");
    }

    /**
     * The correct world probing every sim call a message thread makes through the seams — transform, intake, queue, onDraw (there
     * is no MessageStore seam: snapshots go through the run's Trail). At such a call the run is runnable, so the driver must park
     * in its quiesce before it starts another unit; and at each unit start the driver waits until no run thread it has seen is
     * RUNNABLE (a held call or a backoff parks without a sim call in between). A run at a sim call while a unit starts, or two
     * runs at sim calls at once, is a violation. Every call yields to widen the window.
     */
    static final class Probe implements World {
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger entries = new AtomicInteger();
        final AtomicInteger afterRelease = new AtomicInteger();
        final AtomicInteger afterWake = new AtomicInteger();
        final AtomicInteger distinct = new AtomicInteger();   // message threads seen at a sim call
        final AtomicBoolean platform = new AtomicBoolean();
        private final Set<Thread> runThreads = ConcurrentHashMap.newKeySet();   // seen and not yet TERMINATED
        final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        private final Set<Thread> inside = ConcurrentHashMap.newKeySet();
        private final Map<Long, String> idsBySeq = new ConcurrentHashMap<>();
        private final Map<String, Unit.Kind> resumed = new ConcurrentHashMap<>();   // named by a RELEASE or WAKE, no sim call since
        private volatile Thread driver;
        private volatile Unit starting;   // non-null while the driver is at a unit's start

        @Override public String name() { return "probe"; }

        @Override public void onUnit(Unit unit) {
            if (driver == null) driver = Thread.currentThread();
            starting = unit;
            try {
                for (Thread t : runThreads) {
                    if (t.getState() == Thread.State.TERMINATED) runThreads.remove(t);
                    else while (t.getState() == Thread.State.RUNNABLE) Thread.yield();
                }
            } finally {
                starting = null;
            }
            if (unit.kind() == Unit.Kind.RELEASE || unit.kind() == Unit.Kind.WAKE) resumed.put(unit.messageId().orElseThrow(), unit.kind());
        }

        private <T> T call(String what, String messageId, Supplier<T> body) {
            Thread me = Thread.currentThread();
            Thread d = driver;
            if (d == null || me == d) return body.get();   // the driver's own calls (claims, timers) are not a run's
            if (runThreads.add(me)) distinct.incrementAndGet();
            entries.incrementAndGet();
            if (!me.isVirtual()) platform.set(true);
            Unit.Kind resume = messageId == null ? null : resumed.remove(messageId);
            if (resume == Unit.Kind.RELEASE) afterRelease.incrementAndGet();
            if (resume == Unit.Kind.WAKE) afterWake.incrementAndGet();
            while (d.getState() == Thread.State.RUNNABLE) {   // a runnable run: the driver is on its way into the quiesce
                Unit u = starting;
                if (u != null) {
                    violations.add(me.getName() + " at " + what + " (" + messageId + ") while the driver started " + u);
                    break;
                }
                Thread.yield();
            }
            inside.add(me);
            try {
                int now = inside.size();
                peak.accumulateAndGet(now, Math::max);
                if (now > 1) violations.add(me.getName() + " at " + what + " (" + messageId + ") beside " + inside);
                for (int i = 0; i < 64; i++) Thread.yield();
                Unit u = starting;
                if (u != null) violations.add(me.getName() + " at " + what + " (" + messageId + ") while the driver started " + u);
                return body.get();
            } finally {
                inside.remove(me);
            }
        }

        @Override public Step transform(Step sim) {
            return e -> call("transform", e.id(), () -> sim.apply(e));
        }

        @Override public void onDraw(Draw d) {
            call("draw " + d.kind(), d.messageId(), () -> d);
        }

        @Override public Intake intake(Intake sim) {
            return (arrived, ingress, flow, partitionKey, dedupeKey, abandoned) ->
                    call("save", arrived.id(), () -> sim.save(arrived, ingress, flow, partitionKey, dedupeKey, abandoned));
        }

        @Override public WorkQueue queue(WorkQueue sim, Clock clock) {
            return new WorkQueue() {
                private String id(long seq) { return idsBySeq.get(seq); }
                private Future<List<Item>> noted(Future<List<Item>> items) {
                    return items.onSuccess(list -> list.forEach(i -> idsBySeq.put(i.seq(), i.messageId())));
                }
                @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
                    return call("enqueue", messageId, () -> sim.enqueue(messageId, flow, step, partitionKey));
                }
                @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
                    return call("claim", null, () -> noted(sim.claim(workerId, flow, max, lease)));
                }
                @Override public Future<Boolean> complete(long seq, int attempts) {
                    return call("complete", id(seq), () -> sim.complete(seq, attempts));
                }
                @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
                    return call("completeAndTakeNext", id(seq), () -> sim.completeAndTakeNext(seq, attempts, workerId, lease)
                            .onSuccess(c -> c.next().ifPresent(i -> idsBySeq.put(i.seq(), i.messageId()))));
                }
                @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) {
                    return call("renew", id(seq), () -> sim.renew(seq, attempts, lease));
                }
                @Override public Future<Boolean> hold(long seq, int attempts) { return call("hold", id(seq), () -> sim.hold(seq, attempts)); }
                @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return call("held", null, () -> sim.held(after, limit)); }
                @Override public Future<Boolean> release(long seq) { return call("release", id(seq), () -> sim.release(seq)); }
                @Override public Future<Boolean> hasItems(String flow) { return call("hasItems", null, () -> sim.hasItems(flow)); }
            };
        }
    }

    /** The correct world recording, below the simulation's claim cap, who claims, settles and with what answer. */
    static final class Claims implements World {
        record Call(Thread thread, int max, int answered) {}

        final ConcurrentLinkedQueue<Call> claims = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Thread> chainEnds = new ConcurrentLinkedQueue<>();
        final Set<Thread> runThreads = ConcurrentHashMap.newKeySet();

        @Override public String name() { return "claims"; }

        long chainEndsOff(Thread driver) {
            return chainEnds.stream().filter(t -> t != driver).count();
        }

        @Override public Step transform(Step sim) {
            return e -> {
                runThreads.add(Thread.currentThread());
                return sim.apply(e);
            };
        }

        @Override public WorkQueue queue(WorkQueue sim, Clock clock) {
            return new WorkQueue() {
                @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
                    return sim.enqueue(messageId, flow, step, partitionKey);
                }
                @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
                    Thread caller = Thread.currentThread();
                    return sim.claim(workerId, flow, max, lease).onSuccess(items -> claims.add(new Call(caller, max, items.size())));
                }
                @Override public Future<Boolean> complete(long seq, int attempts) {
                    chainEnds.add(Thread.currentThread());
                    return sim.complete(seq, attempts);
                }
                @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
                    Thread caller = Thread.currentThread();
                    return sim.completeAndTakeNext(seq, attempts, workerId, lease).onSuccess(c -> {
                        if (c.next().isEmpty()) chainEnds.add(caller);
                    });
                }
                @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return sim.renew(seq, attempts, lease); }
                @Override public Future<Boolean> hold(long seq, int attempts) { return sim.hold(seq, attempts); }
                @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return sim.held(after, limit); }
                @Override public Future<Boolean> release(long seq) { return sim.release(seq); }
                @Override public Future<Boolean> hasItems(String flow) { return sim.hasItems(flow); }
            };
        }
    }

    /** The correct world recording the driver's units in order. */
    static final class Units implements World {
        final ConcurrentLinkedQueue<Unit> units = new ConcurrentLinkedQueue<>();

        @Override public String name() { return "units"; }

        @Override public void onUnit(Unit unit) { units.add(unit); }
    }
}
