package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.api.Timer;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-08b, D-055: the worker's driver, its lease and its attempts bound. A {@link FlowWorker} over the in-memory store, a
 * {@link RecordingWorkQueue} (the store boundary, recording every claim, renew, hold and complete), a DLQ that can be taken
 * down, a {@link RecordingComponent} partner and a {@link RunGate} step that holds each run until the test lets it go. The
 * fake clock and the fake timer drive leases, renewals and ticks; nothing waits on wall time but the failure bounds of the
 * waits. Every case and every try of a property builds its own {@link World}.
 */
@Timeout(30)
class FlowWorkerTest {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration TICK = Duration.ofSeconds(1);
    static final Map<String, Object> TO_PARTNER = DeliveryTest.TO_PARTNER;
    /** A step that throws an {@link Error}: no parking catches it, so the item is left to its lease (D-049 c). */
    static final Map<String, Object> CRASH = Map.of("type", "crash", "name", "crash");
    /** A step that hands on a new body written into {@link World#stepBodies}, a store the test makes refuse persists (P1-09j). */
    static final Map<String, Object> REWRITE = Map.of("type", "rewrite", "name", "rewrite");
    /** A step that is not deterministic: each run hands on a body of its own bytes ({@code <run n='k'/>}), written into {@link World#stepBodies} (FIX-35). */
    static final Map<String, Object> STAMP = Map.of("type", "stamp", "name", "stamp");
    /**
     * A step that is not deterministic and hands on a body stored by the testkit's {@code FakeRuntime.store} — under the key the
     * envelope it is given names for the next step (D-066) — each run with bytes of its own ({@code <store n='k'/>}); the key of
     * every body it wrote in {@link World#storedKeys}, the attempt prop each run saw in {@link World#seenAttempts} (P1-09k1).
     */
    static final Map<String, Object> STORE = Map.of("type", "store", "name", "store");

    World w;
    Logs logs;

    @BeforeEach
    void setUp() {
        w = new World();
        logs = Logs.attach();
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        w.close();
    }

    // --- fixtures

    /** What a crashed step throws: an {@link Error}, past every RuntimeException handler of the pipeline. */
    static final class Crash extends Error {
        Crash(String message) { super(message); }
    }

    static WorkerPolicy policy(Duration lease, int maxAttempts) {
        return new WorkerPolicy(lease, maxAttempts, TICK);
    }

    /** An at-least-once flow {@code orders} with these steps and this in-flight request limit (the worker's max). */
    static Map<String, Object> flow(int maxInFlight, List<Map<String, Object>> steps) {
        Map<String, Object> doc = DeliveryTest.flow("at-least-once", steps);
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", maxInFlight));
        return doc;
    }

    static List<Long> seqs(List<WorkQueue.Item> items) {
        return items.stream().map(WorkQueue.Item::seq).toList();
    }

    /** A timer whose tasks stop running once {@link #kill()}ed: the timer of a worker that crashed. */
    static final class KillableTimer implements Timer {
        private final Timer inner;
        private volatile boolean dead;

        KillableTimer(Timer inner) { this.inner = inner; }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            return inner.schedule(delay, () -> { if (!dead) task.run(); });
        }

        void kill() { dead = true; }
    }

    /** Waits (spinning, never sleeping) until {@code condition} holds; fails after a 5 s bound naming what it waited for. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** Everything one case, or one try of a property, runs on. */
    static final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final RecordingComponent partner = new RecordingComponent("mock");
        final HoldingMessageStore store = new HoldingMessageStore(new InMemoryMessageStore());
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
        final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        final InMemoryDlq parked = new InMemoryDlq();
        final FailingDlq dlq = new FailingDlq(parked);
        final RunGate gate = new RunGate();
        final StepFactory crash = ParkingTest.factory("crash", s -> Stage.of(e -> { throw new Crash("crashed at " + s.name()); }));
        /** Where {@code rewrite} steps write the bodies they hand on: not the runtime's store, the ingress stays there. */
        final HoldingBlobStore stepBodies = new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
        final StepFactory rewrite = ParkingTest.rewrite(stepBodies);
        /** The bytes each {@code stamp} run handed on, in run order. */
        final List<Buffer> stamped = new CopyOnWriteArrayList<>();
        final StepFactory stamp = ParkingTest.factory("stamp", s -> Stage.of(e -> {
            Buffer bytes = Buffer.buffer("<run n='" + (stamped.size() + 1) + "'/>");
            stamped.add(bytes);
            return e.next(stepBodies.write(BlobKey.body(e.id(), e.step() + 1), bytes, ParkingTest.MAX).await());
        }));
        /** {@code stamp} under the envelope's next key (D-066): each run's bytes under a key of its own from the second attempt on. */
        final StepFactory stampNext = ParkingTest.factory("stamp-next", s -> Stage.of(e -> {
            Buffer bytes = Buffer.buffer("<run n='" + (stamped.size() + 1) + "'/>");
            stamped.add(bytes);
            return e.next(stepBodies.write(e.nextKey(), bytes, ParkingTest.MAX).await());
        }));
        /** Crashes (an {@link Error}: no park, the item is left to its lease) on a message's first run; a rerun carries its attempt and passes. */
        final StepFactory crashFirst = ParkingTest.factory("crash-first", s -> Stage.of(e -> {
            if (e.get(Envelope.ATTEMPT).isEmpty()) throw new Crash("crashed at " + s.name() + " on the first run");
            return e;
        }));
        /** The key of each body a {@code store} run wrote into the runtime's store, in run order. */
        final List<BlobKey> storedKeys = new CopyOnWriteArrayList<>();
        /** The run's attempt prop ({@link Envelope#ATTEMPT}) each {@code store} run saw, in run order. */
        final List<Optional<Integer>> seenAttempts = new CopyOnWriteArrayList<>();
        final StepFactory storing = ParkingTest.factory("store", s -> Stage.of(e -> {
            seenAttempts.add(e.get(Envelope.ATTEMPT));
            Body body = rt.store(e, Buffer.buffer("<store n='" + (storedKeys.size() + 1) + "'/>"));
            storedKeys.add(body.key());
            return e.next(body);
        }));
        final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        final List<FlowWorker> workers = new CopyOnWriteArrayList<>();

        World() {
            rt.withComponent(partner);
        }

        FlowBuilder builder() {
            return builder(rt.runtime());
        }

        FlowBuilder builder(Runtime runtime) {
            return new FlowBuilder(runtime, List.of(gate, crash, rewrite, stamp, storing, stampNext, crashFirst), List.of(BuiltInStepsTest.HEADERS), endpoints)
                    .recording(store, Meter.system()).queue(queue).dlq(dlq);
        }

        /** The runtime with {@code timer} in place of the fake one: a worker built over it schedules there. */
        Runtime runtimeWith(Timer timer) {
            Runtime r = rt.runtime();
            return new Runtime(r.clock(), timer, r.ids(), r.blobs(), r.hasher(), r.components());
        }

        FlowWorker worker(FlowDefinition def, String id, WorkerPolicy policy) {
            return worker(builder(), def, id, policy);
        }

        FlowWorker worker(FlowBuilder b, FlowDefinition def, String id, WorkerPolicy policy) {
            FlowWorker worker = b.worker(def, NO_FILES, id, policy);
            workers.add(worker);
            return worker;
        }

        Envelope ingress() {
            return rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
        }

        /** A fresh message through the flow's at-least-once delivery: saved and queued, nothing run. */
        Envelope accept(Delivery delivery) {
            Envelope in = ingress();
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());
            return in;
        }

        List<String> sentIds() {
            return partner.sentTo("partner").stream().map(Envelope::id).toList();
        }

        /** Every claim answered (or held by the test) and every item handed out has entered the gate. */
        void settle() {
            until(() -> queue.claimsInFlight() == queue.heldClaims() && gate.entered() == queue.handedOutTotal(),
                    () -> "claims in flight " + queue.claimsInFlight() + " (held " + queue.heldClaims() + "), runs entered " + gate.entered()
                            + " of " + queue.handedOutTotal() + " items handed out; claims " + queue.claims());
        }

        /** Polls {@code worker} until the queue is empty (the gate open). */
        void drain(FlowWorker worker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rows.size() > 0) {
                if (System.nanoTime() - deadline > 0) fail("not drained within 5 s: " + rows.snapshot());
                worker.poll().otherwiseEmpty().await(5, SECONDS);
            }
        }

        /** The single DLQ row. */
        Parked parkedRow() {
            List<Parked> all = parked.list(Optional.empty(), Page.MAX_LIMIT).await().items();
            assertEquals(1, all.size(), "one DLQ row: " + all);
            return all.get(0);
        }

        /** The attempts of every item a claim handed out, in claim order. */
        List<Integer> claimedAttempts() {
            return queue.claims().stream().filter(c -> c.items != null).flatMap(c -> c.items.stream()).map(WorkQueue.Item::attempts).toList();
        }

        void close() {
            workers.forEach(FlowWorker::stop);
            queue.claimsOk();
            queue.renewsOk();
            queue.releaseHeld();
            gate.open();
        }
    }

    /** JUL records of everything under {@code io.flottila.core} (System.Logger resolves to JUL in core's tests). */
    static final class Logs extends Handler {
        private static final Logger CORE = Logger.getLogger("io.flottila.core");   // a strong reference: JUL holds loggers weakly
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        static Logs attach() {
            Logs logs = new Logs();
            logs.setLevel(Level.ALL);
            CORE.addHandler(logs);
            return logs;
        }

        void detach() { CORE.removeHandler(this); }

        @Override public void publish(LogRecord r) { records.add(r); }
        @Override public void flush() { }
        @Override public void close() { }

        /** The text (message and every throwable in its chain) of the records at {@code level} that contain {@code part}. */
        List<String> at(Level level, String part) {
            return records.stream().filter(r -> r.getLevel().equals(level)).map(Logs::text).filter(t -> t.contains(part)).toList();
        }

        List<String> all() { return records.stream().map(r -> r.getLevel() + " " + text(r)).toList(); }

        static String text(LogRecord r) {
            StringBuilder s = new StringBuilder(String.valueOf(new SimpleFormatter().formatMessage(r)));
            chain(s, r.getThrown(), Collections.newSetFromMap(new IdentityHashMap<>()));
            return s.toString();
        }

        private static void chain(StringBuilder s, Throwable t, Set<Throwable> seen) {
            if (t == null || !seen.add(t)) return;
            s.append(" | ").append(t);
            chain(s, t.getCause(), seen);
            for (Throwable suppressed : t.getSuppressed()) chain(s, suppressed, seen);
        }
    }

    // --- the lease, renewed while the item runs

    @Test
    void aSlowMessageKeepsItsLeaseWhileItRuns() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        FlowWorker w2 = w.worker(b, def, "w2", policy(LEASE, 5));

        Future<Integer> round = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled; pending " + w.rt.timer().pending());
        long seq = w.queue.claimsBy("w1").get(0).items.get(0).seq();
        for (int step = 1; step <= 15; step++) {   // five leases, in renewal steps of a third
            w.rt.clock().advance(LEASE.dividedBy(3));
            int renewals = step;
            until(() -> w.queue.renews().size() == renewals && w.rt.timer().pending() == 1, () -> "renewal " + renewals + ": " + w.queue.renews());
            assertEquals(0, w2.poll().await(5, SECONDS), "a second worker polling " + LEASE.dividedBy(3).multipliedBy(step) + " after the claim claims nothing");
            assertEquals(Optional.of("w1"), w.rows.leaseHolder(seq), "still w1's after " + step + " renewals");
        }
        assertTrue(w.queue.renews().stream().allMatch(r -> Boolean.TRUE.equals(r.answer())), "every renewal extended the lease: " + w.queue.renews());
        assertEquals(List.of(), w.partner.sent(), "the run is still held");

        w.gate.runs().get(0).release();
        assertEquals(1, round.await(5, SECONDS));
        assertEquals(List.of(in.id()), w.sentIds(), "sent once, after five leases");
        assertEquals(0, w.rows.size(), "and the item completed");
        assertEquals(1, w.gate.entered(), "one run in all");
        assertTrue(w.queue.claimsBy("w2").stream().allMatch(c -> c.items != null && c.items.isEmpty()), "w2 never got the item: " + w.queue.claimsBy("w2"));
    }

    @Test
    void aLeaseNobodyRenewsExpiresAfterOneLease() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        w.accept(b.delivery(def, NO_FILES));
        KillableTimer crashing = new KillableTimer(w.rt.timer());
        FlowWorker w1 = w.worker(w.builder(w.runtimeWith(crashing)), def, "w1", policy(LEASE, 5));
        FlowWorker w2 = w.worker(b, def, "w2", policy(LEASE, 5));

        w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "w1's run at the gate with its renewal scheduled");
        long seq = w.queue.claimsBy("w1").get(0).items.get(0).seq();
        for (int r = 1; r <= 2; r++) {   // renewed at +10 s and +20 s: leased until +50 s
            w.rt.clock().advance(LEASE.dividedBy(3));
            int renewals = r;
            until(() -> w.queue.renews().size() == renewals && w.rt.timer().pending() == 1, () -> "renewal " + renewals);
        }
        crashing.kill();   // w1 is gone: its run never ends, its timer never fires again

        w.rt.clock().advance(LEASE.minusMillis(1));
        assertEquals(2, w.queue.renews().size(), "nobody renews for a crashed worker");
        assertEquals(0, w2.poll().await(5, SECONDS), "1 ms short of one lease after the last renewal the item is still leased");
        assertEquals(Optional.of("w1"), w.rows.leaseHolder(seq));

        w.rt.clock().advance(Duration.ofMillis(1));
        w2.poll();
        until(() -> w.queue.claimsBy("w2").size() == 2 && w.queue.claimsInFlight() == 0, () -> "w2's second claim: " + w.queue.claimsBy("w2"));
        List<WorkQueue.Item> got = w.queue.claimsBy("w2").get(1).items;
        assertEquals(List.of(seq), seqs(got), "one lease after the last renewal another worker takes the item over");
        assertEquals(2, got.get(0).attempts(), "the crashed worker's claim counted as an attempt");
        assertEquals(Optional.of("w2"), w.rows.leaseHolder(seq));
    }

    @Test
    void renewalRunsAtAThirdOfTheLease() throws Exception {
        Duration lease = Duration.ofMillis(1_500);   // a third is 500 ms: not a whole number of seconds
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(lease, 5));

        Future<Integer> round = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        RecordingWorkQueue.ClaimCall claim = w.queue.claimsBy("w1").get(0);
        assertEquals(lease, claim.lease, "the claim leases for the policy's lease");
        WorkQueue.Item item = claim.items.get(0);
        assertEquals(Duration.ofMillis(500), w.rt.timer().nextDelay(), "the first renewal is due a third of the lease after the claim");

        w.rt.clock().advance(Duration.ofMillis(499));
        assertEquals(List.of(), w.queue.renews(), "not before a third of the lease");
        w.rt.clock().advance(Duration.ofMillis(1));
        until(() -> w.queue.renews().size() == 1 && w.rt.timer().pending() == 1, () -> "the first renewal, rescheduled");
        assertEquals(new RecordingWorkQueue.RenewCall(item.seq(), 1, lease, FakeRuntime.T0.plusMillis(500), true), w.queue.renews().get(0),
                "renewed with the claim's seq and attempts, for a whole lease");
        assertEquals(Duration.ofMillis(500), w.rt.timer().nextDelay(), "and again a third of the lease later");
        w.rt.clock().advance(Duration.ofMillis(500));
        until(() -> w.queue.renews().size() == 2 && w.rt.timer().pending() == 1, () -> "the second renewal");
        assertEquals(FakeRuntime.T0.plusMillis(1_000), w.queue.renews().get(1).at());

        w.gate.runs().get(0).release();
        assertEquals(1, round.await(5, SECONDS));
        w.gate.joinPassed();
        assertEquals(0, w.rt.timer().pending(), "the renewal is cancelled when the run ends");
        w.rt.clock().advance(lease.multipliedBy(3));
        assertEquals(2, w.queue.renews().size(), "no renewal after the run ended");
    }

    @Test
    void aRenewalThatFailsIsRetriedAtTheNextInterval() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");

        w.queue.failRenews(new IllegalStateException("pg away"));
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 1 && w.rt.timer().pending() == 1, () -> "the failed renewal, rescheduled");
        assertNull(w.queue.renews().get(0).answer(), "the renewal failed");
        assertEquals(LEASE.dividedBy(3), w.rt.timer().nextDelay(), "a failed renewal is tried again a third of the lease later");
        assertFalse(logs.at(Level.WARNING, in.id()).isEmpty(), "a failed renewal is a WARNING naming the message: " + logs.all());

        w.queue.renewsOk();
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 2 && w.rt.timer().pending() == 1, () -> "the renewal after the failed one");
        assertEquals(Boolean.TRUE, w.queue.renews().get(1).answer(), "renewing goes on after a failure and extends the lease");
    }

    // --- the driver

    @Test
    void theSameWorkerNeverRunsAnItemTwiceAtOnce() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> first = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        long seq = w.queue.claimsBy("w1").get(0).items.get(0).seq();
        w.queue.answerRenews(false);   // somebody else's lease now, as far as the queue is concerned
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 1, () -> "the renewal");
        assertEquals(0, w.rt.timer().pending(), "a lost lease is not renewed again");
        assertFalse(logs.at(Level.WARNING, in.id()).isEmpty(), "the lost lease is a WARNING naming the message: " + logs.all());
        w.queue.renewsOk();

        w.rt.clock().advance(LEASE);   // the claim's lease ran out long ago
        Future<Integer> again = w1.poll();
        until(() -> w.queue.claimsBy("w1").size() == 2 && w.queue.claimsInFlight() == 0, () -> "the re-claim: " + w.queue.claims());
        List<WorkQueue.Item> reclaimed = w.queue.claimsBy("w1").get(1).items;
        assertEquals(List.of(seq), seqs(reclaimed), "the same worker re-claims the item it still runs");
        assertEquals(2, reclaimed.get(0).attempts());

        w.gate.runs().get(0).release();
        assertEquals(1, first.await(5, SECONDS));
        w.gate.open();   // a second run, had one started, passes now and its round waits for it
        again.otherwiseEmpty().await(5, SECONDS);
        w.gate.joinPassed();
        assertEquals(List.of(), w.gate.overlaps(), "never two runs of the message at once");
        assertEquals(1, w.gate.entered(), "the re-claim started no run");
        assertEquals(List.of(in.id()), w.sentIds(), "sent once");
        assertEquals(0, w.rows.size());
    }

    @Test
    void aPollRequestedWhileAClaimIsInFlightRunsOnceAfterIt() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowWorker w1 = w.worker(def, "w1", policy(LEASE, 5));
        w.queue.holdNext();

        Future<Integer> first = w1.poll();
        until(() -> w.queue.heldClaims() == 1, () -> "the first claim, held open");
        Future<Integer> second = w1.poll();
        Future<Integer> third = w1.poll();
        assertEquals(1, w.queue.claims().size(), "no claim while one is in flight");
        assertFalse(second.isComplete(), "a poll requested meanwhile waits for the round after the claim");
        assertFalse(third.isComplete());

        w.queue.releaseHeld();
        assertEquals(0, first.await(5, SECONDS));
        assertEquals(0, second.await(5, SECONDS));
        assertEquals(0, third.await(5, SECONDS));
        assertEquals(2, w.queue.claims().size(), "the two polls requested during the claim share one follow-up round: " + w.queue.claims());
        assertEquals(1, w.queue.peakClaimsInFlight(), "never two claims in flight");
    }

    @Test
    void aFailedClaimStillRunsTheSharedFollowUpRound() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.gate.open();
        w.queue.holdNext();

        Future<Integer> first = w1.poll();
        until(() -> w.queue.heldClaims() == 1, () -> "the first claim, held open");
        Future<Integer> second = w1.poll();
        Future<Integer> third = w1.poll();
        assertEquals(1, w.queue.claims().size(), "no claim while one is in flight");
        IllegalStateException cause = new IllegalStateException("pg away");
        w.queue.failNextClaim(cause);
        w.queue.releaseHeld();

        until(first::isComplete, () -> "the round of the failed claim ends");
        assertTrue(first.failed(), "the round of the failed claim fails");
        assertSame(cause, first.cause());
        assertEquals(1, second.await(5, SECONDS), "the follow-up round runs although the claim before it failed, and claims the backlog");
        assertEquals(1, third.await(5, SECONDS), "the same round, shared");
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(in.id()), w.sentIds());
        List<RecordingWorkQueue.ClaimCall> claims = w.queue.claims();
        assertEquals(3, claims.size(), "the failed claim, exactly one follow-up round, the finished item's poll: " + claims);
        assertSame(cause, claims.get(0).failure);
        assertEquals(List.of(in.id()), claims.get(1).items.stream().map(WorkQueue.Item::messageId).toList(), "the follow-up round claimed the item");
        assertEquals(List.of(), claims.get(2).items, "the finished item's poll found nothing");
        assertEquals(1, w.queue.peakClaimsInFlight(), "never two claims in flight");
        assertEquals(0, w.rows.size());
    }

    @Test
    void aClaimThatThrowsDoesNotWedgeTheWorker() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.gate.open();
        IllegalStateException thrown = new IllegalStateException("claim threw");
        w.queue.throwOnNextClaim(thrown);

        Future<Integer> failed = assertDoesNotThrow(() -> w1.poll(), "a claim that throws fails the poll's future, never its caller");
        until(failed::isComplete, () -> "the round of the throwing claim ends");
        assertTrue(failed.failed(), "the round of the throwing claim fails");
        assertSame(thrown, failed.cause());

        assertEquals(1, w1.poll().await(5, SECONDS), "the next poll claims: the worker is not left claiming forever");
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(in.id()), w.sentIds());
        assertEquals(List.of(in.id()), w.queue.claims().get(1).items.stream().map(WorkQueue.Item::messageId).toList(), "the second claim took the item");
        assertEquals(0, w.rows.size());
    }

    @Test
    void theTimerPollsOnItsInterval() {
        Duration every = Duration.ofMillis(1_500);
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowWorker w1 = w.worker(def, "w1", new WorkerPolicy(LEASE, 5, every));
        w1.start();
        until(() -> w.queue.claimsInFlight() == 0, () -> "start settled");
        int c0 = w.queue.claims().size();
        assertEquals(every, w.rt.timer().nextDelay(), "the first tick is due one poll interval after start");

        w.rt.clock().advance(every);
        until(() -> w.queue.claims().size() == c0 + 1 && w.queue.claimsInFlight() == 0, () -> "the first tick's claim: " + w.queue.claims());
        assertEquals(every, w.rt.timer().nextDelay(), "the next tick an interval later");
        w.rt.clock().advance(every);
        until(() -> w.queue.claims().size() == c0 + 2 && w.queue.claimsInFlight() == 0, () -> "the second tick's claim");

        w.queue.failClaims(new IllegalStateException("claims down"));
        assertDoesNotThrow(() -> w.rt.clock().advance(every), "a tick's failure is never thrown into the timer");
        until(() -> w.queue.claims().size() == c0 + 3 && w.queue.claimsInFlight() == 0, () -> "the failing tick's claim");
        until(() -> !logs.at(Level.WARNING, "claims down").isEmpty() || !logs.at(Level.SEVERE, "claims down").isEmpty(),
                () -> "the tick's failure logged: " + logs.all());
        w.queue.claimsOk();
        w.rt.clock().advance(every);
        until(() -> w.queue.claims().size() == c0 + 4 && w.queue.claimsInFlight() == 0, () -> "a tick after the failure: the ticks go on");
        assertNotNull(w.queue.claims().get(c0 + 3).items);
    }

    @Test
    void aStoppedWorkerPollsNoMore() {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope first = w.accept(delivery);
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w1.start();
        w.rt.clock().advance(TICK);
        until(() -> w.gate.entered() == 1 && w.queue.claimsInFlight() == 0, () -> "the ticked claim's run at the gate");

        w1.stop();
        int claims = w.queue.claims().size();
        Envelope second = w.accept(delivery);
        assertEquals(1, w.rt.timer().pending(), "the tick is cancelled; the running item's renewal is not");
        for (int i = 0; i < 3; i++) w.rt.clock().advance(TICK);
        assertEquals(claims, w.queue.claims().size(), "no tick after stop");

        w.rt.clock().advance(w.rt.timer().nextDelay());
        until(() -> w.queue.renews().size() == 1, () -> "the running item's renewal after stop");
        assertEquals(Boolean.TRUE, w.queue.renews().get(0).answer());

        RunGate.Run run = w.gate.runs().get(0);
        run.release();
        run.joined();
        assertEquals(List.of(first.id()), w.sentIds(), "the running item finished");
        assertEquals(claims, w.queue.claims().size(), "and started no new round on a stopped worker");
        assertEquals(List.of(second.id()), w.rows.snapshot().stream().map(WorkQueue.Item::messageId).toList(), "the second message waits for a started worker");
        assertEquals(0, w.rt.timer().pending(), "nothing scheduled any more");
    }

    @Test
    void anAcceptedDeliveryPolls() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        Function<Envelope, Future<Ack>> handler = w1.pollingAfterAccept(b.delivery(def, NO_FILES));
        w.gate.open();

        Envelope in = w.ingress();
        assertEquals(new Ack.Accepted(in.id()), handler.apply(in).await(5, SECONDS));
        w.partner.awaitSent(1);
        assertEquals(List.of(in.id()), w.sentIds(), "the accept polled: no tick, no explicit poll");
        until(() -> w.rows.size() == 0, () -> "the item completed");
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");

        int claims = w.queue.claims().size();
        w.queue.failClaims(new IllegalStateException("claims down"));
        Envelope next = w.ingress();
        assertEquals(new Ack.Accepted(next.id()), handler.apply(next).await(5, SECONDS), "a failing poll never fails the accept");
        until(() -> w.queue.claims().size() == claims + 1 && w.queue.claimsInFlight() == 0, () -> "the accept's claim");
        until(() -> !logs.at(Level.WARNING, "claims down").isEmpty() || !logs.at(Level.SEVERE, "claims down").isEmpty(),
                () -> "the trigger's failure logged: " + logs.all());
    }

    @Test
    void aNackDoesNotPoll() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope queued = w.accept(b.delivery(def, NO_FILES));   // what a poll would claim
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Promise<Ack> later = Promise.promise();
        Future<Ack> nack = w1.pollingAfterAccept(e -> later.future()).apply(w.ingress());
        later.complete(new Ack.Nack("busy", true));
        assertEquals(new Ack.Nack("busy", true), nack.await(5, SECONDS), "the Nack passes through");
        Ack.Reply reply = new Ack.Reply(queued);
        assertEquals(reply, w1.pollingAfterAccept(e -> Future.succeededFuture(reply)).apply(w.ingress()).await(5, SECONDS));
        IllegalStateException refused = new IllegalStateException("handler failed");
        Future<Ack> failed = w1.pollingAfterAccept(e -> Future.<Ack>failedFuture(refused)).apply(w.ingress());
        until(failed::isComplete, () -> "the failed handler's answer");
        assertSame(refused, failed.cause(), "a failed handler's future passes through");
        assertEquals(List.of(), w.queue.claims(), "neither a Nack, a Reply nor a failure polls");
        assertEquals(1, w.rows.size());

        Future<Ack> accepted = w1.pollingAfterAccept(e -> Future.succeededFuture(new Ack.Accepted(e.id()))).apply(w.ingress());
        accepted.await(5, SECONDS);
        until(() -> w.gate.entered() == 1 && w.queue.claimsInFlight() == 0, () -> "the Accepted's poll ran the queued item");
        assertEquals(1, w.queue.claims().size(), "the same wrapper polls on Accepted, once");
        assertEquals(List.of(queued.id()), w.queue.claims().get(0).items.stream().map(WorkQueue.Item::messageId).toList());
    }

    @Test
    void aFinishedItemPollsForTheBacklog() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(1, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        List<String> ids = List.of(w.accept(delivery).id(), w.accept(delivery).id(), w.accept(delivery).id());
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.gate.open();

        assertEquals(1, w1.poll().await(5, SECONDS), "one poll claims one item: max 1");
        w.partner.awaitSent(3);
        until(() -> w.rows.size() == 0 && w.gate.exited() == 3, () -> "the backlog drained: " + w.rows.snapshot());
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(ids, w.sentIds(), "all three ran, in order, without a tick");
        assertEquals(4, w.queue.claims().size(), "one poll, then one per finished item; the last finds nothing: " + w.queue.claims());
        assertTrue(w.queue.claims().stream().allMatch(c -> c.max == 1), "each asks for max − running = 1: " + w.queue.claims());
        assertEquals(0, w.rt.timer().pending(), "the worker was never started: no tick");
    }

    @Test
    void aFailedCompleteFailsTheRoundAndFreesItsSlot() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(1, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope first = w.accept(delivery);
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.gate.open();
        IllegalStateException cause = new IllegalStateException("pg away");
        w.queue.failCompletes(1, cause);

        Future<Integer> round = w1.poll();
        until(round::isComplete, () -> "the round ends");
        assertTrue(round.failed(), "a completion that fails fails the round");
        assertSame(cause, round.cause());
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(first.id()), w.sentIds(), "the item ran");
        long firstSeq = w.queue.claims().get(0).items.get(0).seq();
        assertEquals(List.of(firstSeq), w.queue.completes(), "its completion was tried once");
        assertEquals(List.of(firstSeq), w.rows.snapshot().stream().map(WorkQueue.Item::seq).toList(), "the failed completion leaves the row to its lease");

        Envelope second = w.accept(delivery);
        assertEquals(1, w1.poll().await(5, SECONDS), "the slot of the item whose completion failed is free again");
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        List<RecordingWorkQueue.ClaimCall> took = w.queue.claims().stream()
                .filter(c -> c.items != null && c.items.stream().anyMatch(i -> i.messageId().equals(second.id()))).toList();
        assertEquals(1, took.size(), "one claim took the second message: " + w.queue.claims());
        assertEquals(1, took.get(0).max, "it asked for the full room, max 1 − nothing running");
        assertEquals(List.of(first.id(), second.id()), w.sentIds());
        assertEquals(List.of(firstSeq), w.rows.snapshot().stream().map(WorkQueue.Item::seq).toList(), "the second completed; the first waits for its lease");
    }

    // --- Q-1 (D-074): the worker settles under the attempts it holds

    /**
     * w1 runs the item at attempt 3; its lease is found lost (the renewal answered false), runs out, and w2 claims the item again
     * (attempt 4). When w1's run ends its complete, fenced by attempt 3, answers false: the row stays w2's as w2 was handed it; one
     * WARNING names the message, the flow, the work item, the attempt and the worker; {@code staleSettles} counts it; the round
     * succeeds and the complete is never tried again.
     */
    @Test
    void aStaleCompleteIsAWarningAndLeavesTheRowToItsNewHolder() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        for (int i = 1; i <= 6; i++) w.rows.enqueue("pad-" + i, "elsewhere", 1, null).await();   // the item's seq is 7: only the work item says 7
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        for (int k = 1; k <= 2; k++) {   // two workers claimed it and died: w1's claim is the third attempt
            assertEquals(k, w.rows.claim("crashed-" + k, "orders", 1, LEASE).await().get(0).attempts(), "fixture");
            w.rt.clock().advance(LEASE);
        }
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> round = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        WorkQueue.Item mine = w.queue.claimsBy("w1").get(0).items.get(0);
        assertEquals(7, mine.seq(), "fixture");
        assertEquals(3, mine.attempts(), "fixture");
        w.queue.answerRenews(false);
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 1, () -> "the renewal, answered false");
        w.queue.renewsOk();
        w.rt.clock().advance(LEASE);   // w1's lease ran out
        WorkQueue.Item theirs = w.rows.claim("w2", "orders", 1, LEASE).await().get(0);
        assertEquals(new WorkQueue.Item(mine.seq(), in.id(), "orders", 1, null, 4), theirs, "fixture: w2 claims the item w1 still runs");

        w.gate.runs().get(0).release();
        assertEquals(1, round.await(5, SECONDS), "the round succeeds: a stale complete is no failure");
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "the finished item's poll answered");
        assertEquals(List.of(in.id()), w.sentIds(), "the run itself went through");
        assertEquals(List.of(new RecordingWorkQueue.CompleteCall(mine.seq(), 3, false)), w.queue.completeCalls(),
                "one complete, fenced by w1's attempt 3, answered false, never tried again");
        assertEquals(List.of(theirs), w.rows.snapshot().stream().filter(i -> i.flow().equals("orders")).toList(), "the row stays as w2 was handed it");
        assertEquals(Optional.of("w2"), w.rows.leaseHolder(mine.seq()), "leased to its new holder");

        List<String> stale = logs.at(Level.WARNING, "left to its holder");
        assertEquals(1, stale.size(), "one WARNING: " + logs.all());
        String line = stale.get(0);
        assertTrue(line.contains(in.id()), "names the message: " + line);
        assertTrue(line.contains("orders"), "names the flow: " + line);
        assertTrue(Pattern.compile("work item\\W*7\\b").matcher(line).find(), "names the work item: " + line);
        assertTrue(Pattern.compile("attempt\\W*3\\b").matcher(line).find(), "names the attempt: " + line);
        assertTrue(line.contains("w1"), "names the worker: " + line);
        assertTrue(line.contains("claimed again, held or completed meanwhile"), "says what became of it: " + line);
        assertEquals(1, w1.staleSettles(), "counted");
        assertEquals(List.of(), logs.at(Level.SEVERE, in.id()), "no ERROR: " + logs.all());

        assertEquals(0, w1.poll().await(5, SECONDS), "w2's lease runs: nothing to claim");
        assertEquals(1, w.queue.completeCalls().size(), "still one complete: " + w.queue.completeCalls());
        assertEquals(1, w1.staleSettles());
    }

    /**
     * Past the bound and unparkable, w1 holds the item fenced by its attempt 2 — but its lease ran out and w2 claimed the item
     * again (attempt 3) just before the hold arrived: the hold answers false, nothing is held, the row stays w2's; the ERROR says
     * the item was not held, and {@code staleSettles} counts the hold.
     */
    @Test
    void aStaleHoldIsCounted() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        assertEquals(1, w.rows.claim("crashed", "orders", 1, LEASE).await().get(0).attempts(), "a worker claimed it and died at once");
        w.rt.clock().advance(LEASE);
        w.dlq.failParks(new IllegalStateException("dlq down"));
        long seq = w.rows.snapshot().get(0).seq();
        List<WorkQueue.Item> theirs = new CopyOnWriteArrayList<>();
        w.queue.onHold(s -> {   // between the failed park and the hold: w1's lease runs out and w2 claims the item again
            w.rt.clock().advance(LEASE);
            theirs.addAll(w.rows.claim("w2", "orders", 1, LEASE).await());
        });
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 1));

        assertEquals(1, w1.poll().await(5, SECONDS), "the round succeeds: the hold answered");
        assertEquals(List.of(new WorkQueue.Item(seq, in.id(), "orders", 1, null, 3)), theirs, "fixture: w2 claimed it before the hold arrived");
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, 2, false)), w.queue.holds(), "the hold, fenced by w1's attempt 2, answered false");
        assertEquals(List.of(), w.rows.held(Optional.empty(), Page.MAX_LIMIT).await().items(), "nothing held");
        assertEquals(Optional.of("w2"), w.rows.leaseHolder(seq), "the row stays w2's");
        List<String> errors = logs.at(Level.SEVERE, in.id());
        assertEquals(1, errors.size(), "one ERROR naming the message: " + logs.all());
        assertTrue(errors.get(0).contains("not held:"), "says the item was not held: " + errors.get(0));
        assertFalse(errors.get(0).contains("held for a human"), "never that it was held: " + errors.get(0));
        assertTrue(errors.get(0).contains("dlq down"), "names the park failure: " + errors.get(0));
        assertEquals(1, w1.staleSettles(), "a stale hold counts");
    }

    /**
     * w1's lease is found lost while its run goes on, the lease runs out, and w1 itself claims the item again (attempt 2): no
     * second run, the claim is adopted — the renewal, stopped when the lease was found lost, resumes a third of the lease later
     * fenced by attempt 2 and extends the lease; the run's end completes the item under attempt 2, and nothing is stale.
     */
    @Test
    void aReclaimedRunRenewsAndCompletesUnderItsNewAttempts() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> first = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        long seq = w.queue.claimsBy("w1").get(0).items.get(0).seq();
        w.queue.answerRenews(false);
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 1, () -> "the renewal, answered false");
        assertEquals(0, w.rt.timer().pending(), "fixture: the lease found lost, nothing scheduled");
        w.queue.renewsOk();
        w.rt.clock().advance(LEASE);   // the claim's lease ran out

        Future<Integer> again = w1.poll();
        until(() -> w.queue.claimsBy("w1").size() == 2 && w.queue.claimsInFlight() == 0, () -> "the re-claim: " + w.queue.claims());
        assertEquals(List.of(new WorkQueue.Item(seq, in.id(), "orders", 1, null, 2)), w.queue.claimsBy("w1").get(1).items, "fixture: w1 claims its own item again");
        until(() -> w.rt.timer().pending() == 1, () -> "the renewal resumed under the adopted claim; renewals " + w.queue.renews());
        assertEquals(LEASE.dividedBy(3), w.rt.timer().nextDelay(), "a third of the lease after the re-claim");
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 2 && w.rt.timer().pending() == 1, () -> "the renewal under attempt 2: " + w.queue.renews());
        RecordingWorkQueue.RenewCall renewed = w.queue.renews().get(1);
        assertEquals(List.of(seq, 2L), List.of(renewed.seq(), (long) renewed.attempts()), "fenced by the adopted attempt 2: " + renewed);
        assertEquals(Boolean.TRUE, renewed.answer(), "and extends the lease");

        w.gate.runs().get(0).release();
        assertEquals(1, first.await(5, SECONDS));
        again.otherwiseEmpty().await(5, SECONDS);
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(new RecordingWorkQueue.CompleteCall(seq, 2, true)), w.queue.completeCalls(), "completed under the adopted attempt 2");
        assertEquals(0, w.rows.size());
        assertEquals(0, w1.staleSettles(), "nothing stale");
        assertEquals(List.of(), logs.at(Level.WARNING, "left to its holder"), logs.all().toString());
        assertEquals(1, w.gate.entered(), "one run");
        assertEquals(List.of(in.id()), w.sentIds());
    }

    /**
     * The other branch of the adoption: w1's renewals fail (the store is away), so its lease is never found lost, yet it runs out
     * while the next renewal is scheduled. w1 claims its own item again (attempt 2) and adopts the claim without a second renewal:
     * the one already scheduled fires fenced by attempt 2 — the token is read when it fires, not when it was scheduled.
     */
    @Test
    void aReclaimWhileARenewalIsScheduledKeepsOneRenewalUnderTheNewAttempts() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> first = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        long seq = w.queue.claimsBy("w1").get(0).items.get(0).seq();
        w.queue.failRenews(new IllegalStateException("pg away"));
        for (int r = 1; r <= 3; r++) {   // the renewals at +10 s, +20 s and +30 s fail: the claim's lease runs out at +30 s
            w.rt.clock().advance(LEASE.dividedBy(3));
            int renewals = r;
            until(() -> w.queue.renews().size() == renewals && w.rt.timer().pending() == 1, () -> "failed renewal " + renewals + ": " + w.queue.renews());
        }
        w.queue.renewsOk();

        w1.poll();
        until(() -> w.queue.claimsBy("w1").size() == 2 && w.queue.claimsInFlight() == 0, () -> "the re-claim: " + w.queue.claims());
        assertEquals(List.of(new WorkQueue.Item(seq, in.id(), "orders", 1, null, 2)), w.queue.claimsBy("w1").get(1).items, "fixture: w1 claims its own item again");
        assertEquals(1, w.rt.timer().pending(), "one renewal scheduled: the adoption adds none");
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 4 && w.rt.timer().pending() == 1, () -> "the scheduled renewal: " + w.queue.renews());
        RecordingWorkQueue.RenewCall renewed = w.queue.renews().get(3);
        assertEquals(List.of(seq, 2L), List.of(renewed.seq(), (long) renewed.attempts()), "fenced by the adopted attempt 2: " + renewed);
        assertEquals(Boolean.TRUE, renewed.answer(), "and extends the lease");

        w.gate.runs().get(0).release();
        assertEquals(1, first.await(5, SECONDS));
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(new RecordingWorkQueue.CompleteCall(seq, 2, true)), w.queue.completeCalls(), "completed under the adopted attempt 2");
        assertEquals(0, w1.staleSettles());
    }

    /**
     * Q-1 (implementer): a lease resumed by an adoption renews as any other. w1's lease is found lost, runs out, and w1 claims its
     * item again (attempt 2): the renewal resumes. Then the store is away, the renewals fail, the lease runs out once more and w1
     * claims the item a third time (attempt 3): that adoption finds the renewal going and adds none — still one scheduled, fenced by
     * attempt 3 when it fires — and the run's end completes the item under attempt 3.
     */
    @Test
    void aSecondReclaimAfterAResumedRenewalKeepsOneRenewal() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> first = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        long seq = w.queue.claimsBy("w1").get(0).items.get(0).seq();
        w.queue.answerRenews(false);
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 1, () -> "the renewal, answered false");
        assertEquals(0, w.rt.timer().pending(), "fixture: the lease found lost, nothing scheduled");
        w.queue.renewsOk();
        w.rt.clock().advance(LEASE);   // the first claim's lease ran out

        w1.poll();
        until(() -> w.queue.claimsBy("w1").size() == 2 && w.queue.claimsInFlight() == 0, () -> "the second claim: " + w.queue.claims());
        assertEquals(2, w.queue.claimsBy("w1").get(1).items.get(0).attempts(), "fixture: attempt 2");
        assertEquals(1, w.rt.timer().pending(), "fixture: the renewal resumed");
        w.queue.failRenews(new IllegalStateException("pg away"));
        for (int r = 2; r <= 4; r++) {   // the renewals 10 s, 20 s and 30 s after the second claim fail: its lease runs out at 30 s
            w.rt.clock().advance(LEASE.dividedBy(3));
            int renewals = r;
            until(() -> w.queue.renews().size() == renewals && w.rt.timer().pending() == 1, () -> "failed renewal " + renewals + ": " + w.queue.renews());
        }
        w.queue.renewsOk();

        w1.poll();
        until(() -> w.queue.claimsBy("w1").size() == 3 && w.queue.claimsInFlight() == 0, () -> "the third claim: " + w.queue.claims());
        assertEquals(List.of(new WorkQueue.Item(seq, in.id(), "orders", 1, null, 3)), w.queue.claimsBy("w1").get(2).items, "fixture: attempt 3");
        assertEquals(1, w.rt.timer().pending(), "one renewal scheduled: the second adoption found the renewal going and added none");
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 5 && w.rt.timer().pending() == 1, () -> "the scheduled renewal: " + w.queue.renews());
        RecordingWorkQueue.RenewCall renewed = w.queue.renews().get(4);
        assertEquals(List.of(seq, 3L), List.of(renewed.seq(), (long) renewed.attempts()), "fenced by attempt 3: " + renewed);
        assertEquals(Boolean.TRUE, renewed.answer(), "and extends the lease");

        w.gate.runs().get(0).release();
        assertEquals(1, first.await(5, SECONDS));
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(new RecordingWorkQueue.CompleteCall(seq, 3, true)), w.queue.completeCalls(), "completed under attempt 3");
        assertEquals(0, w1.staleSettles());
        assertEquals(1, w.gate.entered(), "one run");
    }

    /**
     * Q-1 (implementer): the hold is fenced by the lease's token too. w1's claim is past the bound (attempt 2 of maxAttempts 1)
     * and its park waits on the DLQ; meanwhile its lease is found lost, runs out, and w1 claims the item again (attempt 3), adopting
     * the claim. The park then fails: the item is held under attempt 3 — w1 is its current holder — and nothing is stale.
     */
    @Test
    void anAdoptedClaimIsHeldUnderItsAttempts() throws Exception {
        List<Promise<Void>> parks = new CopyOnWriteArrayList<>();
        io.flottila.core.dlq.Dlq waiting = new io.flottila.core.dlq.Dlq() {
            @Override public Future<Void> park(Parked p) {
                Promise<Void> answer = Promise.promise();
                parks.add(answer);
                return answer.future();
            }
            @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return w.parked.list(after, limit); }
            @Override public Future<Optional<Parked>> get(String messageId) { return w.parked.get(messageId); }
            @Override public Future<Void> remove(String messageId) { return w.parked.remove(messageId); }
        };
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = new FlowBuilder(w.rt.runtime(), List.of(w.gate), List.of(BuiltInStepsTest.HEADERS), w.endpoints)
                .recording(w.store, Meter.system()).queue(w.queue).dlq(waiting);
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        assertEquals(1, w.rows.claim("crashed", "orders", 1, LEASE).await().get(0).attempts(), "a worker claimed it and died at once");
        w.rt.clock().advance(LEASE);
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 1));

        Future<Integer> round = w1.poll();
        until(() -> parks.size() == 1 && w.rt.timer().pending() == 1, () -> "the park of attempt 2 waiting, its renewal scheduled");
        long seq = w.rows.snapshot().get(0).seq();
        w.queue.answerRenews(false);
        w.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> w.queue.renews().size() == 1 && w.rt.timer().pending() == 0, () -> "the lease found lost");
        w.queue.renewsOk();
        w.rt.clock().advance(LEASE);   // the claim's lease ran out
        w1.poll();
        until(() -> w.queue.claimsBy("w1").size() == 2 && w.queue.claimsInFlight() == 0, () -> "the re-claim: " + w.queue.claims());
        assertEquals(List.of(new WorkQueue.Item(seq, in.id(), "orders", 1, null, 3)), w.queue.claimsBy("w1").get(1).items, "fixture: attempt 3, adopted");

        parks.get(0).fail(new IllegalStateException("dlq down"));
        assertEquals(1, round.await(5, SECONDS));
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, 3, true)), w.queue.holds(), "held under the adopted attempt 3");
        assertEquals(List.of(new WorkQueue.Item(seq, in.id(), "orders", 1, null, 3)),
                w.rows.held(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(WorkQueue.Held::item).toList(), "the item is held");
        assertEquals(0, w1.staleSettles(), "nothing stale");
        List<String> errors = logs.at(Level.SEVERE, in.id());
        assertEquals(1, errors.size(), logs.all().toString());
        assertTrue(errors.get(0).contains("held for a human"), errors.get(0));
        assertEquals(0, w.gate.entered(), "past the bound: no run");
    }

    // --- the attempts bound

    @Test
    void anItemPastMaxAttemptsIsParkedWithoutRunning() throws Exception {
        int maxAttempts = 3;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER, CRASH)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        w.gate.open();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            assertEquals(1, w1.poll().await(5, SECONDS), "attempt " + attempt + ": the round claimed the item and ended without failing");
            w.gate.joinPassed();
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
            assertEquals(attempt, w.partner.sentTo("partner").size(), "attempt " + attempt + " ran and crashed unparked");
            assertEquals(1, w.rows.size(), "an Error leaves the item to its lease");
            String counted = "attempt " + attempt + " of " + maxAttempts;
            List<String> warned = logs.at(Level.WARNING, in.id()).stream().filter(t -> t.contains(counted)).toList();
            assertEquals(1, warned.size(), "one WARNING naming the message and '" + counted + "': " + logs.all());
            assertTrue(warned.get(0).contains("crashed at crash"), "names the failure: " + warned.get(0));
            w.rt.clock().advance(LEASE);
        }
        assertEquals(1, w1.poll().await(5, SECONDS), "the claim past the bound");
        w.gate.joinPassed();
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");

        assertEquals(maxAttempts, w.partner.sentTo("partner").size(), "sent exactly maxAttempts times: the claim past the bound does not run");
        assertEquals(maxAttempts, w.gate.entered());
        assertEquals(List.of(1, 2, 3, 4), w.claimedAttempts(), "every claim of the item counted");
        Parked p = w.parkedRow();
        assertEquals(in.id(), p.messageId());
        assertEquals(1, p.step(), "parked at step 1");
        assertEquals("gate", p.stepName());
        assertTrue(p.error().contains("AttemptsExhaustedException"), p.error());
        assertEquals(in.body().key(), p.bodyKey(), "the ingress envelope, rebuilt, by reference");
        assertEquals(Map.of("flow", "orders@0.1.0"), p.versions(), "parked under the flow's versions");
        assertEquals(w.rt.clock().instant(), p.parkedAt(), "parked at the runtime clock's instant");
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(List.of(), w.queue.holds());
        assertEquals(List.of(), logs.at(Level.WARNING, "attempt " + (maxAttempts + 1) + " of"), "the claim past the bound is no failed attempt");
    }

    @Test
    void aCrashLoopCountsAsAttempts() throws Exception {
        int maxAttempts = 2;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        Timer dead = (delay, task) -> () -> true;   // a killed worker's timer: nothing it scheduled ever fires
        FlowBuilder killed = w.builder(w.runtimeWith(dead));
        for (int k = 1; k <= maxAttempts; k++) {
            w.worker(killed, def, "k" + k, policy(LEASE, maxAttempts)).poll();   // claimed; the run never ends: killed mid-run
            int entered = k;
            until(() -> w.gate.entered() == entered && w.queue.claimsInFlight() == 0, () -> "killed run " + entered + " at the gate");
            w.rt.clock().advance(LEASE);
        }

        FlowWorker healthy = w.worker(b, def, "h", policy(LEASE, maxAttempts));
        healthy.poll().otherwiseEmpty().await(5, SECONDS);   // a run, had one started, would hang at the gate and this would time out
        assertEquals(maxAttempts, w.gate.entered(), "past the bound the item is parked without a run");
        assertEquals(List.of(), w.partner.sent());
        assertEquals(List.of(1, 2, 3), w.claimedAttempts(), "claims whose runs never finished counted");
        assertEquals(List.of(3), w.queue.claimsBy("h").get(0).items.stream().map(WorkQueue.Item::attempts).toList());
        Parked p = w.parkedRow();
        assertEquals(in.id(), p.messageId());
        assertEquals(1, p.step());
        assertTrue(p.error().contains("AttemptsExhaustedException"), p.error());
        assertEquals(0, w.rows.size());
    }

    @Test
    void anItemThatCannotBeParkedIsHeldAndAlerted() throws Exception {
        int maxAttempts = 2;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        w.gate.open();
        w.partner.failNext(100, () -> new NackException("order refused", false));
        w.dlq.failParks(new IllegalStateException("dlq down"));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            w1.poll().otherwiseEmpty().await(5, SECONDS);
            w.gate.joinPassed();
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
            assertEquals(attempt, w.partner.sent().size(), "attempt " + attempt + " ran; the DLQ refused the park");
            w.rt.clock().advance(LEASE);
        }
        w1.poll().otherwiseEmpty().await(5, SECONDS);
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        long seq = w.rows.snapshot().get(0).seq();
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, maxAttempts + 1, true)), w.queue.holds(),
                "past the bound and unparkable: held, fenced by the claim's attempts");
        assertEquals(1, w.rows.size(), "the row stays: the sender holds a 202 for it");
        List<String> errors = logs.at(Level.SEVERE, in.id());
        assertEquals(1, errors.size(), "one ERROR naming the message: " + logs.all());
        String alert = errors.get(0);
        assertTrue(alert.contains("orders"), "names the flow: " + alert);
        assertTrue(Pattern.compile("\\b" + (maxAttempts + 1) + "\\b").matcher(alert).find(), "names the attempts (" + (maxAttempts + 1) + "): " + alert);
        assertTrue(alert.contains("dlq down"), "names the cause: " + alert);

        for (int lease = 1; lease <= 10; lease++) {
            w.rt.clock().advance(LEASE);
            assertEquals(0, w1.poll().await(5, SECONDS), "a held row is never claimed again (lease " + lease + ")");
        }
        assertEquals(maxAttempts, w.partner.sent().size(), "no endless send");
        assertEquals(1, logs.at(Level.SEVERE, in.id()).size(), "one alert, not one per lease");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items());
        assertEquals(1, w.queue.holds().size());
        assertEquals(1, w.rows.size());
        assertEquals(0, w1.staleSettles(), "Q-1: a hold that answered true is no stale settle");
    }

    /**
     * P1-09c1 (D-067): a release keeps the row's attempts, so an item held past the bound (its park refused) is, once the DLQ is
     * back and a human released it, parked at step 1 on its very next claim — no clock advance: the release cleared the lease as
     * well as the hold — without another run, and completed; nothing is sent again, nothing held again, no second alert.
     */
    @Test
    void aReleasedExhaustedItemIsParkedAtStepOneOnItsNextClaim() throws Exception {
        int maxAttempts = 2;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        w.gate.open();
        w.partner.failNext(100, () -> new NackException("order refused", false));
        w.dlq.failParks(new IllegalStateException("dlq down"));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            w1.poll().otherwiseEmpty().await(5, SECONDS);
            w.gate.joinPassed();
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
            w.rt.clock().advance(LEASE);
        }
        assertEquals(1, w1.poll().await(5, SECONDS), "fixture: the claim past the bound");
        long seq = w.rows.snapshot().get(0).seq();
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, maxAttempts + 1, true)), w.queue.holds(), "fixture: past the bound and unparkable, held");
        assertEquals(1, logs.at(Level.SEVERE, in.id()).size(), "fixture: one alert");
        w.dlq.parksOk();
        assertEquals(0, w1.poll().await(5, SECONDS), "fixture: held, not claimed with the DLQ back either");
        assertEquals(maxAttempts, w.partner.sent().size(), "fixture: two runs, both refused by the partner");

        assertTrue(w.queue.release(seq).await(), "a human releases the held item");
        assertEquals(1, w1.poll().await(5, SECONDS), "claimed on the next poll, the clock where the hold left it: the release cleared the lease too");

        assertEquals(List.of(1, 2, 3, 4), w.claimedAttempts(), "the release kept the attempts: the claim after it is the fourth");
        assertEquals(maxAttempts, w.gate.entered(), "past the bound: parked without a run");
        assertEquals(maxAttempts, w.partner.sent().size(), "nothing sent again");
        Parked p = w.parkedRow();
        assertEquals(in.id(), p.messageId());
        assertEquals(1, p.step(), "parked at step 1");
        assertEquals("gate", p.stepName());
        assertTrue(p.error().contains("AttemptsExhaustedException") && p.error().contains("claimed " + (maxAttempts + 2) + " times"),
                "exhausted, counting the attempts kept through the release: " + p.error());
        assertEquals(in.body().key(), p.bodyKey(), "the ingress envelope, rebuilt, by reference");
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(1, w.queue.holds().size(), "not held again");
        assertEquals(1, logs.at(Level.SEVERE, in.id()).size(), "no second alert: " + logs.all());
    }

    @Test
    void anItemWhoseTimelineCannotBeReadAtTheBoundIsHeld() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        assertEquals(1, w.rows.claim("crashed", "orders", 1, LEASE).await().get(0).attempts(), "a worker claimed it and died at once");
        w.rt.clock().advance(LEASE);
        w.store.failReads(new IllegalStateException("pg away"));

        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 1));
        w1.poll().otherwiseEmpty().await(5, SECONDS);
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        long seq = w.rows.snapshot().get(0).seq();
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, 2, true)), w.queue.holds(), "past the bound, the timeline unreadable: held");
        List<String> errors = logs.at(Level.SEVERE, in.id());
        assertEquals(1, errors.size(), "one ERROR naming the message: " + logs.all());
        assertTrue(errors.get(0).contains("orders") && errors.get(0).contains("pg away"), errors.get(0));
        assertEquals(0, w.gate.entered());
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items());

        w.store.readsOk();
        w.rt.clock().advance(LEASE);
        assertEquals(0, w1.poll().await(5, SECONDS), "held: not claimed once the store is back either");
        assertEquals(1, w.rows.size());
    }

    @Test
    void anUnparkableItemIsAlertedWhateverTheHoldAnswers() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        assertEquals(1, w.rows.claim("crashed", "orders", 1, LEASE).await().get(0).attempts(), "a worker claimed it and died at once");
        w.rt.clock().advance(LEASE);
        w.dlq.failParks(new IllegalStateException("dlq down"));
        long seq = w.rows.snapshot().get(0).seq();
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 1));

        // the hold answers false (its fence lost): nothing is held, the alert is raised all the same
        w.queue.answerHolds(false);
        w1.poll().otherwiseEmpty().await(5, SECONDS);
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, 2, false)), w.queue.holds(), "past the bound and unparkable: a hold was tried");
        List<String> errors = logs.at(Level.SEVERE, in.id());
        assertEquals(1, errors.size(), "one ERROR naming the message although the hold answered false: " + logs.all());
        String refused = errors.get(0);
        assertTrue(refused.contains("orders"), "names the flow: " + refused);
        assertTrue(Pattern.compile("\\b2\\b").matcher(refused).find(), "names the attempts (2): " + refused);
        assertTrue(refused.contains("dlq down"), "names the park failure: " + refused);

        // the hold itself fails: nothing is held, nothing answered — the alert is raised all the same, naming both failures
        w.queue.answerHolds(null);
        w.queue.failHolds(1, new IllegalStateException("pg away"));
        w.rt.clock().advance(LEASE);
        w1.poll().otherwiseEmpty().await(5, SECONDS);
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(new RecordingWorkQueue.HoldCall(seq, 2, false), new RecordingWorkQueue.HoldCall(seq, 3, null)), w.queue.holds(),
                "claimed again past the bound, unparkable again: a hold was tried and failed");
        List<String> later = logs.at(Level.SEVERE, in.id()).stream().filter(t -> !t.equals(refused)).toList();
        assertTrue(later.stream().anyMatch(t -> t.contains("dlq down") && t.contains("pg away")),
                "an ERROR naming the message, the park failure and the hold failure: " + logs.all());
    }

    @Test
    void aGhostItemIsCompletedWithAWarning() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowWorker w1 = w.worker(def, "w1", policy(LEASE, 5));
        w.queue.enqueue("ghost", "orders", 1, null).await();   // no ingress snapshot: nothing to rebuild it from

        assertEquals(1, w1.poll().await(5, SECONDS));
        assertEquals(0, w.rows.size(), "completed, never a poison loop");
        assertFalse(logs.at(Level.WARNING, "ghost").isEmpty(), "a WARNING naming the message: " + logs.all());
        assertEquals(List.of(), logs.at(Level.SEVERE, "ghost"));
        assertEquals(0, w.gate.entered());
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    @Test
    void aGhostPastTheBoundIsCompletedNotHeld() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        w.queue.enqueue("ghost", "orders", 1, null).await();
        assertEquals(1, w.rows.claim("crashed", "orders", 1, LEASE).await().get(0).attempts(), "a worker claimed it and died at once");
        w.rt.clock().advance(LEASE);

        FlowWorker w1 = w.worker(def, "w1", policy(LEASE, 1));
        w1.poll().otherwiseEmpty().await(5, SECONDS);
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(2), w.claimedAttempts(), "claimed past the bound");
        assertEquals(0, w.rows.size(), "a ghost past the bound is completed like any ghost");
        assertEquals(List.of(), w.queue.holds(), "not held: there is nothing a human could retry");
        assertFalse(logs.at(Level.WARNING, "ghost").isEmpty(), "a WARNING naming the message: " + logs.all());
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    /**
     * P1-09j (D-062): a park whose body cannot be made durable fails, writes no row and surfaces the step's failure, so the
     * worker leaves the item to its lease — a WARNING, no completion, no hold — and the next claim runs it again.
     */
    @Test
    void anItemWhoseParkCannotPersistIsLeftToItsLease() throws Exception {
        int maxAttempts = 3;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(REWRITE, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        BlobKey stepBody = BlobKey.body(in.id(), 1);
        BodyTooLargeException tooLarge = new BodyTooLargeException(stepBody, 70_000, 65_536);
        w.stepBodies.refuse(tooLarge);
        w.partner.failNext(100, () -> new NackException("order refused", false));

        assertEquals(1, w1.poll().await(5, SECONDS), "the round claimed the item and ended without failing");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(1, w.partner.sent().size(), "fixture: the run reached the partner, which refused");
        assertEquals(List.of(stepBody, stepBody), w.stepBodies.persists(),
                "the park, then the run's trail (D-064: a failed park persist leaves the key not durable), asked to persist the rewritten body");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "no DLQ row: the body could not be made durable");
        assertEquals(1, w.rows.size(), "the item stays queued");
        assertEquals(List.of(), w.queue.completes(), "not completed");
        assertEquals(List.of(), w.queue.holds(), "not held: within the bound");
        String counted = "attempt 1 of " + maxAttempts;
        List<String> warned = logs.at(Level.WARNING, in.id()).stream().filter(t -> t.contains(counted)).toList();
        assertEquals(1, warned.size(), "one WARNING naming the message and '" + counted + "': " + logs.all());
        assertTrue(warned.get(0).contains("left to its lease"), warned.get(0));
        assertTrue(warned.get(0).contains("order refused"), "names the step's failure: " + warned.get(0));
        assertTrue(warned.get(0).contains(tooLarge.getMessage()), "and, suppressed, the persist's: " + warned.get(0));
        assertEquals(List.of(), logs.at(Level.SEVERE, in.id()));

        assertEquals(0, w1.poll().await(5, SECONDS), "leased: nobody claims it before its lease ran out");
        w.rt.clock().advance(LEASE);
        assertEquals(1, w1.poll().await(5, SECONDS), "after its lease the item is claimed again");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of(1, 2), w.claimedAttempts(), "the second claim is attempt 2");
        assertEquals(2, w.partner.sent().size(), "and ran again");
        assertEquals(1, w.rows.size());
    }

    /**
     * P1-09j (D-062): past the bound, a message whose step body can never be made durable is parked by its ingress at step 1 —
     * durable since its delivery (D-058) — without a run; its item completes.
     */
    @Test
    void pastTheBoundAnUnpersistableStepBodyParksTheIngressAtTheFirstStep() throws Exception {
        int maxAttempts = 2;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(REWRITE, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        BlobKey stepBody = BlobKey.body(in.id(), 1);
        w.stepBodies.refuse(new BodyTooLargeException(stepBody, 70_000, 65_536));
        w.partner.failNext(100, () -> new NackException("order refused", false));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            assertEquals(1, w1.poll().await(5, SECONDS), "attempt " + attempt);
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
            assertEquals(attempt, w.partner.sent().size(), "attempt " + attempt + " ran");
            assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "attempt " + attempt + ": no DLQ row, the step body is not durable");
            assertEquals(1, w.rows.size(), "attempt " + attempt + ": left to its lease");
            w.rt.clock().advance(LEASE);
        }
        assertEquals(1, w1.poll().await(5, SECONDS), "the claim past the bound");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");

        assertEquals(maxAttempts, w.partner.sent().size(), "the claim past the bound does not run");
        assertEquals(List.of(1, 2, 3), w.claimedAttempts());
        Parked p = w.parkedRow();
        assertEquals(in.id(), p.messageId());
        assertEquals(1, p.step(), "parked at step 1");
        assertEquals("rewrite", p.stepName());
        assertEquals(in.body().key(), p.bodyKey(), "the ingress, by reference");
        assertTrue(p.error().contains("AttemptsExhaustedException"), p.error());
        assertTrue(w.rt.blobs().persisted(in.body().key()), "the ingress body is durable");
        assertEquals(List.of(stepBody, stepBody, stepBody, stepBody), w.stepBodies.persists(),
                "the step body was tried by each run's park and each run's trail (D-064), never by the exhausted park");
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(List.of(), w.queue.holds());
        assertEquals(List.of(), logs.at(Level.SEVERE, in.id()), "no alert: the park succeeded");
    }

    /**
     * FIX-35 (D-061 amended): a key's durable bytes never change. Attempt 1 parks: its step body is made durable, then the
     * DLQ refuses the row, so the item is left to its lease. The rerun's step is not deterministic and writes other bytes under
     * the same key: its park's persist fails with an {@link IllegalStateException}, no DLQ row describes bytes its key does
     * not hold, and the item is left to its lease again; past the bound the message parks its ingress at step 1.
     */
    @Test
    void aRerunWhoseParkedBodyDiffersFromItsDurableBytesEndsInTheIngressPark() throws Exception {
        int maxAttempts = 2;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(STAMP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        BlobKey stepBody = BlobKey.body(in.id(), 1);
        w.partner.failNext(100, () -> new NackException("order refused", false));
        w.dlq.failParks(new IllegalStateException("dlq down"));

        assertEquals(1, w1.poll().await(5, SECONDS), "attempt 1");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(1, w.partner.sent().size(), "fixture: attempt 1 ran and the partner refused");
        assertEquals(List.of(stepBody), w.stepBodies.persists(), "fixture: attempt 1's park persisted its step body");
        assertTrue(w.stepBodies.persisted(stepBody), "fixture: attempt 1's step body is durable");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "fixture: the DLQ refused the row");
        assertEquals(1, w.rows.size(), "fixture: left to its lease");
        w.dlq.parksOk();
        w.rt.clock().advance(LEASE);

        assertEquals(1, w1.poll().await(5, SECONDS), "attempt 2");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(2, w.partner.sent().size(), "attempt 2 ran and the partner refused");
        assertEquals(2, w.stamped.size(), "fixture: stamp ran twice");
        assertFalse(w.stamped.get(0).equals(w.stamped.get(1)), "fixture: the rerun wrote other bytes: " + w.stamped);
        assertEquals(w.stamped.get(1), w.stepBodies.read(stepBody).await(), "fixture: the key holds the rerun's bytes locally");
        assertEquals(List.of(stepBody, stepBody, stepBody), w.stepBodies.persists(),
                "attempt 2's park and attempt 2's trail (D-064: its park's persist failed) asked to persist the step body too");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(),
                "no DLQ row: the key's durable bytes are attempt 1's, not the bytes attempt 2 parked");
        assertEquals(1, w.rows.size(), "the item stays queued");
        assertEquals(List.of(), w.queue.completes(), "not completed");
        assertEquals(List.of(), w.queue.holds(), "not held: within the bound");
        String counted = "attempt 2 of " + maxAttempts;
        List<String> warned = logs.at(Level.WARNING, in.id()).stream().filter(t -> t.contains(counted)).toList();
        assertEquals(1, warned.size(), "one WARNING naming the message and '" + counted + "': " + logs.all());
        assertTrue(warned.get(0).contains("order refused"), "names the step's failure: " + warned.get(0));
        assertTrue(warned.get(0).contains("the durable body under " + stepBody + " holds other bytes than these"),
                "and, suppressed, the persist's refusal: " + warned.get(0));
        w.rt.clock().advance(LEASE);

        assertEquals(1, w1.poll().await(5, SECONDS), "the claim past the bound");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(maxAttempts, w.partner.sent().size(), "the claim past the bound does not run");
        assertEquals(List.of(1, 2, 3), w.claimedAttempts());
        Parked p = w.parkedRow();
        assertEquals(in.id(), p.messageId());
        assertEquals(1, p.step(), "parked at step 1");
        assertEquals("stamp", p.stepName());
        assertEquals(in.body().key(), p.bodyKey(), "the ingress, by reference");
        assertTrue(p.error().contains("AttemptsExhaustedException"), p.error());
        assertTrue(w.rt.blobs().persisted(in.body().key()), "the ingress body is durable");
        assertEquals(List.of(stepBody, stepBody, stepBody), w.stepBodies.persists(), "the exhausted park does not touch the step body");
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(List.of(), w.queue.holds());
        assertEquals(List.of(), logs.at(Level.SEVERE, in.id()), "no alert: the park succeeded");
    }

    /**
     * P1-09k1 (D-066): the worker runs the n-th claim of an item as attempt n. A step that writes the body it hands on under the
     * envelope's next key writes the first attempt's under the plain key, as before, and each later attempt's under a key of
     * its own ({@code body~n}), so a step that is not deterministic leaves every run's bytes where that run put them: the
     * step's recorded row (the first run's, one row per step) still reads the bytes it describes after two reruns.
     */
    @Test
    void aSecondAttemptWritesItsStepBodiesUnderItsAttempt() throws Exception {
        int maxAttempts = 3;
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(STORE, CRASH)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            assertEquals(1, w1.poll().await(5, SECONDS), "attempt " + attempt);
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
            assertEquals(attempt, w.storedKeys.size(), "attempt " + attempt + " ran the store step");
            assertEquals(1, w.rows.size(), "attempt " + attempt + ": the crash leaves the item to its lease");
            w.rt.clock().advance(LEASE);
        }
        assertEquals(List.of(1, 2, 3), w.claimedAttempts(), "fixture: the item's three claims are attempts 1, 2 and 3");
        assertEquals(List.of(Optional.empty(), Optional.of(2), Optional.of(3)), w.seenAttempts,
                "the first claim runs as no attempt in particular, every later one carries the claim's attempt");
        List<Snapshot> rows = w.store.timeline(in.id()).await().stream().filter(s -> "store".equals(s.stepName())).toList();
        assertEquals(1, rows.size(), "fixture: one row per step and message, the first run's: " + rows);
        BlobKey rowKey = rows.get(0).bodyKey().orElseThrow();
        assertEquals(BlobKey.body(in.id(), 1), rowKey, "the first attempt's row refers to the step's plain key, as before D-066");

        assertEquals(List.of(BlobKey.body(in.id(), 1), new BlobKey(in.id(), 1, "body~2"), new BlobKey(in.id(), 1, "body~3")), w.storedKeys,
                "attempts 2 and 3 write the step's body under keys of their own");
        for (int run = 1; run <= maxAttempts; run++) {
            assertEquals("<store n='" + run + "'/>", w.rt.blobs().read(w.storedKeys.get(run - 1)).await().toString(), "run " + run + "'s bytes stay under its key");
        }
        assertEquals(rows.get(0).bodyHash(), w.rt.hasher().hashOf(w.rt.blobs().read(rowKey).await()), "the row still reads the bytes it describes");
    }

    // --- P1-09k2 (D-064, D-066, F-10): the run trail

    /** {@code t} ended, or parked waiting (never a transient state): where a run that may wait for its trail has got to. */
    static boolean endedOrWaiting(Thread t) {
        Thread.State st = t.getState();
        return st == Thread.State.TERMINATED || st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING;
    }

    /** The step names of {@code messageId}'s rows in the store, in step order. */
    List<String> rowNames(String messageId) {
        return w.store.timeline(messageId).await().stream().map(Snapshot::stepName).toList();
    }

    /**
     * The worker completes an item only once its run's trail closed: the run's step body persisted, then the rows written. At
     * the {@code complete} call the step body is durable and every row of the run is in the store. The ingress — the run's
     * seed, made durable by the delivery (D-058) — is not persisted again although the gate's row shows it.
     */
    @Test
    void anItemCompletesOnlyAfterItsRunsBodiesPersisted() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, REWRITE, TO_PARTNER)));
        FlowBuilder b = w.builder();
        String id = w.rt.ids().next();
        Envelope in = Envelope.ingress(id, "orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                w.stepBodies.write(BlobKey.body(id, 0), ORDER, ParkingTest.MAX).await());
        assertEquals(new Ack.Accepted(id), b.delivery(def, NO_FILES).apply(in).await());
        assertEquals(List.of(in.body().key()), w.stepBodies.persists(), "fixture: the delivery made the ingress durable in its own store");
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 3));
        BlobKey stepBody = BlobKey.body(id, 2);   // rewrite is step 2 (after the gate): it writes under (id, 2)
        List<String> atComplete = new CopyOnWriteArrayList<>();
        w.queue.onComplete(seq -> atComplete.add("step body durable " + w.stepBodies.persisted(stepBody) + ", rows " + rowNames(id)));
        w.gate.open();
        w.stepBodies.hold();

        Future<Integer> polled = w1.poll();
        until(() -> w.gate.entered() == 1, () -> "the run entered the gate");
        Thread run = w.gate.runs().get(0).thread;
        until(() -> w.stepBodies.held() == 1 && (!atComplete.isEmpty() || (w.gate.runs().get(0).exited && endedOrWaiting(run))),
                () -> "the trail's persist asked and the run waiting or done: held " + w.stepBodies.held() + ", " + run.getState() + ", completes " + atComplete);
        assertEquals(List.of(), atComplete, "no complete while the run's step body is not durable");
        assertEquals(List.of("ingress"), rowNames(id), "the run's rows wait for their body: only the delivery's ingress row so far");
        assertFalse(polled.isComplete(), "the round waits for its item: " + polled);

        w.stepBodies.release();
        assertEquals(1, polled.await(5, SECONDS));
        assertEquals(List.of("step body durable true, rows [ingress, gate, rewrite, partner]"), atComplete,
                "completed once, after the step body was durable and the run's rows were written");
        assertEquals(List.of(in.body().key(), stepBody), w.stepBodies.persists(), "the step body once; the seed never again");
        assertEquals(0, w.rows.size());
    }

    /**
     * Recording is monitoring: a run whose trail cannot persist its step body nor write its rows — a row write that throws,
     * the others failing — still went through, so its item completes; it is neither left to its lease nor parked.
     */
    @Test
    void aRecordingFailureNeitherLeavesNorParksTheItem() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(REWRITE, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 3));
        w.stepBodies.refuse(new IllegalStateException("bodies down"));
        w.store.throwNext(new IllegalStateException("store threw"));
        w.store.hold();

        Future<Integer> polled = w1.poll();
        until(() -> w.store.recorded("rewrite") + w.store.recorded("partner") == 2 && w.store.held() == 1,
                () -> "the run's two rows handed to the store: rewrite " + w.store.recorded("rewrite") + ", partner " + w.store.recorded("partner") + ", held " + w.store.held());
        w.store.fail(new IllegalStateException("store down"));
        assertEquals(1, polled.await(5, SECONDS));
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");

        assertEquals(1, w.partner.sent().size(), "fixture: the run went through to the partner");
        assertEquals(1, w.queue.completes().size(), "the item is completed: a recording failure does not change the verdict");
        assertEquals(0, w.rows.size(), "not left to its lease");
        assertEquals(List.of(), w.queue.holds());
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "not parked");
        assertEquals(List.of(), logs.at(Level.WARNING, in.id()).stream().filter(t -> t.contains("left to its lease")).toList());
        assertEquals(List.of(), logs.at(Level.SEVERE, in.id()));
    }

    /**
     * D-066 with the trail (F-8's FIX-35 note): the first run of a message stamps its step body and crashes, so its trail made
     * the first run's bytes durable under the plain key. The rerun stamps other bytes under a key of its own ({@code body~2}),
     * so its park at the partner step persists without meeting the first run's durable bytes: the message is parked at the
     * step that failed, by the rerun's body — never at the ingress past the attempts bound — and the stamp row (the first
     * run's) still describes durable bytes.
     */
    @Test
    void aRerunOfAStampingNextKeyStepParksAtItsStepNotTheIngress() throws Exception {
        int maxAttempts = 2;
        Map<String, Object> stampNext = Map.of("type", "stamp-next", "name", "stamp");
        Map<String, Object> crashFirst = Map.of("type", "crash-first", "name", "crash-first");
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(stampNext, crashFirst, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, maxAttempts));
        BlobKey first = BlobKey.body(in.id(), 1);
        BlobKey rerun = new BlobKey(in.id(), 1, "body~2");
        w.partner.failNext(100, () -> new NackException("order refused", false));

        assertEquals(1, w1.poll().await(5, SECONDS), "attempt 1");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(1, w.rows.size(), "fixture: attempt 1 crashed, its item is left to its lease");
        assertTrue(w.stepBodies.persisted(first), "attempt 1's trail made its stamp body durable");
        w.rt.clock().advance(LEASE);

        assertEquals(1, w1.poll().await(5, SECONDS), "attempt 2");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(2, w.stamped.size(), "fixture: stamp ran twice");
        assertNotEquals(w.stamped.get(0), w.stamped.get(1), "fixture: the rerun stamped other bytes");
        Parked p = w.parkedRow();
        assertEquals(3, p.step(), "parked at the step that failed, not at the ingress");
        assertEquals("partner", p.stepName());
        assertEquals(rerun, p.bodyKey(), "by the rerun's body, under its attempt's key");
        assertTrue(w.stepBodies.persisted(rerun));
        assertEquals(w.stamped.get(1), w.stepBodies.read(rerun).await());
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(List.of(1, 2), w.claimedAttempts());
        Snapshot stampRow = w.store.timeline(in.id()).await().stream().filter(s -> "stamp".equals(s.stepName())).findFirst().orElseThrow();
        assertEquals(Optional.of(first), stampRow.bodyKey(), "the stamp row is the first run's");
        assertEquals(w.rt.hasher().hashOf(w.stamped.get(0)), stampRow.bodyHash(), "fixture: it describes the first run's bytes");
        assertTrue(w.stepBodies.persisted(first), "and they are durable");
        assertEquals(w.stamped.get(0), w.stepBodies.read(first).await(), "the plain key still holds the first run's bytes");
        assertEquals(List.of(), logs.at(Level.SEVERE, in.id()));
    }

    /**
     * FIX-107: a partner whose send fails with a checked exception — the http producer's refused connection is netty's
     * {@code AnnotatedConnectException}, a {@link java.net.ConnectException}, which {@code Future.await()} rethrows as is — is
     * a step failure like a Nack: the retryable {@code to} step is retried by the flow's policy on the runtime's timer, then
     * the message is parked at that step by its input; the item is completed by the one claim that ran it, nothing is left to
     * its lease and nothing logged at WARNING.
     */
    @Test
    void aPartnerFailingWithACheckedExceptionIsRetriedThenParkedAtItsStep() throws Exception {
        int retries = 2;
        Map<String, Object> doc = flow(4, List.of(STAMP, Map.of("type", "to", "name", "partner", "endpoint", "partner", "retryable", true)));
        doc.put("retry", Map.of("attempts", retries, "initial", "100ms", "max", "2s"));
        FlowDefinition def = FlowDefinition.from(doc);
        List<Duration> retryWaits = new CopyOnWriteArrayList<>();
        Timer timer = (delay, task) -> {
            Timer.Cancellable pending = w.rt.timer().schedule(delay, task);
            // a wait the run schedules on its own thread (flottila-worker-<worker>-<seq>) is a retry's; recorded once pending
            if (Thread.currentThread().getName().startsWith("flottila-worker-")) retryWaits.add(delay);
            return pending;
        };
        FlowBuilder b = w.builder(w.runtimeWith(timer));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.partner.failNext(100, () -> new java.net.ConnectException("Connection refused"));

        Future<Integer> round = w1.poll();
        for (int fired = 0; ; fired++) {
            int done = fired;
            until(() -> round.isComplete() || retryWaits.size() > done, () -> "the run ends or waits for retry " + (done + 1) + "; " + logs.all());
            if (retryWaits.size() == fired) break;
            assertTrue(fired < retries, "no more retries than the policy's " + retries + ": " + retryWaits);
            assertEquals(retryWaits.get(fired), w.rt.timer().nextDelay(), "fixture: the retry's wait is the earliest task");
            w.rt.clock().advance(retryWaits.get(fired));
        }
        assertEquals(1, round.await(5, SECONDS), "one claim ran it");
        until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");

        List<Parked> rows = w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items();
        assertEquals(1, rows.size(), "one DLQ row after the retries: " + rows + "; sends " + w.partner.sent().size() + "; logs " + logs.all());
        Parked p = rows.get(0);
        assertEquals(2, p.step(), "parked at the to step, by its index");
        assertEquals("partner", p.stepName(), "by its name");
        assertEquals(BlobKey.body(in.id(), 1), p.bodyKey(), "by the to step's input, the body stamp handed on");
        assertTrue(p.error().contains("ConnectException"), "the partner's failure is the row's error: " + p.error());
        assertEquals(1 + retries, w.partner.sent().size(), "the partner was tried once and retried " + retries + " times");
        assertEquals(List.of(def.retry().delayBefore(1), def.retry().delayBefore(2)), retryWaits, "the retries waited the policy's delays");
        assertEquals(1, w.stamped.size(), "stamp ran once: the retries re-ran only the to step");
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(List.of(1), w.claimedAttempts(), "by the one claim that ran it");
        assertEquals(List.of(), logs.at(Level.WARNING, in.id()), "nothing left to its lease: " + logs.all());
    }

    /**
     * FIX-107: a step's {@link InterruptedException} — a send whose future failed with it, rethrown as is by {@code await()} —
     * is a cancelled run, not the step's failure: through the worker the retryable {@code to} step is not retried (no backoff
     * on the run's thread, one send), not parked (no DLQ row, the item not completed), the run fails and the item is left to
     * its lease with the worker's WARNING — written on the thread that ran the step, whose interrupt flag is set again by then.
     */
    @Test
    void anInterruptedExceptionFromARetryableStepIsNeitherRetriedNorParkedAndTheRunsThreadKeepsTheInterrupt() throws Exception {
        Map<String, Object> doc = flow(4, List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner", "retryable", true)));
        doc.put("retry", Map.of("attempts", 2, "initial", "100ms", "max", "2s"));
        FlowDefinition def = FlowDefinition.from(doc);
        List<Duration> retryWaits = new CopyOnWriteArrayList<>();
        Timer timer = (delay, task) -> {
            Timer.Cancellable pending = w.rt.timer().schedule(delay, task);
            // a wait the run schedules on its own thread (flottila-worker-<worker>-<seq>) is a retry's
            if (Thread.currentThread().getName().startsWith("flottila-worker-")) retryWaits.add(delay);
            return pending;
        };
        FlowBuilder b = w.builder(w.runtimeWith(timer));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.partner.failNext(1, () -> new InterruptedException("cancelled"));
        List<String> atTheWarning = new CopyOnWriteArrayList<>();   // "<thread> interrupted=<flag>" of each WARNING naming the message
        Logger jul = Logger.getLogger(FlowWorker.class.getName());
        Handler onTheRunsThread = new Handler() {
            @Override public void publish(LogRecord r) {   // runs synchronously on the thread that logs
                if (r.getLevel().equals(Level.WARNING) && String.valueOf(r.getMessage()).contains(in.id())) {
                    atTheWarning.add(Thread.currentThread().getName() + " interrupted=" + Thread.currentThread().isInterrupted());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        onTheRunsThread.setLevel(Level.ALL);
        jul.addHandler(onTheRunsThread);
        try {
            Future<Integer> round = w1.poll();
            until(() -> round.isComplete() || !retryWaits.isEmpty(), () -> "the run ends or waits for a retry; " + logs.all());
            assertEquals(List.of(), retryWaits, "no backoff: an interrupt is never retried");
            assertEquals(1, round.await(5, SECONDS), "one claim ran it");
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        } finally {
            jul.removeHandler(onTheRunsThread);
        }
        assertEquals(1, w.partner.sent().size(), "one send, no retry");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "a cancelled run is never parked");
        assertEquals(1, w.rows.size(), "not parked, not completed: the item is left to its lease");
        assertEquals(List.of(1), w.claimedAttempts());
        List<String> warnings = logs.at(Level.WARNING, in.id());
        assertEquals(1, warnings.size(), logs.all().toString());
        assertTrue(warnings.get(0).contains("left to its lease") && warnings.get(0).contains("java.lang.InterruptedException: cancelled"),
                "the run failed with the InterruptedException itself: " + warnings.get(0));
        String runThread = "flottila-worker-w1-" + w.queue.claims().stream().filter(c -> c.items != null).flatMap(c -> c.items.stream()).findFirst().orElseThrow().seq();
        assertEquals(List.of(runThread + " interrupted=true"), atTheWarning, "the interrupt flag is set again on the thread that ran the step");
    }

    /**
     * The worker's trail is seeded with the ingress key (P1-09k2 r1b): the delivery made the ingress durable (D-058), so a
     * row that shows it — the gate's, which hands the ingress on — keeps the key and the trail never persists it again. The
     * worker rebuilds the ingress in the runtime's store, here the recording one, where the same trail persists the
     * rewritten step body: a persist of the ingress would be seen.
     */
    @Test
    void theWorkersTrailNeverPersistsTheDeliveredIngressAgain() throws Exception {
        io.flottila.api.Runtime r = w.rt.runtime();
        FlowBuilder b = w.builder(new io.flottila.api.Runtime(r.clock(), r.timer(), r.ids(), w.stepBodies, r.hasher(), r.components()));
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, REWRITE, TO_PARTNER)));
        String id = w.rt.ids().next();
        Envelope in = Envelope.ingress(id, "orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                w.stepBodies.write(BlobKey.body(id, 0), ORDER, ParkingTest.MAX).await());
        assertEquals(new Ack.Accepted(id), b.delivery(def, NO_FILES).apply(in).await());
        assertEquals(List.of(in.body().key()), w.stepBodies.persists(), "fixture: the delivery made the ingress durable in the runtime's store");
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 3));
        w.gate.open();

        assertEquals(1, w1.poll().await(5, SECONDS));
        until(() -> w.queue.completes().size() == 1, () -> "the item completed");
        BlobKey stepBody = BlobKey.body(id, 2);   // rewrite is step 2, after the gate
        assertEquals(List.of(in.body().key(), stepBody), w.stepBodies.persists(), "the trail persisted the step body in the same store, never the ingress");
        Snapshot gateRow = w.store.timeline(id).await().stream().filter(s -> "gate".equals(s.stepName())).findFirst().orElseThrow();
        assertEquals(Optional.of(in.body().key()), gateRow.bodyKey(), "the gate's row shows the ingress and keeps its key: it is durable");
    }

    /**
     * {@code t} parked in a {@code Future.await()} that {@link io.flottila.core.dlq.Parking} called: the run waits for its park,
     * every listener of the park's persist registered on the run's thread already.
     */
    static boolean waitingInPark(Thread t) {
        if (t.getState() != Thread.State.WAITING) return false;
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 1; i < stack.length; i++) {
            if (stack[i].getClassName().equals(io.flottila.core.dlq.Parking.class.getName())) return "await".equals(stack[i - 1].getMethodName());
        }
        return false;
    }

    /**
     * D-066 when the park's persist answers on another thread (P1-09k2, M26): the run parks at the partner step under its
     * trail; the persist of the parked body is held until the run waits on it, then answered from the test thread, where no
     * trail is bound. The park still tells the run's trail — the one bound where the park started — that the key is durable,
     * so the trail's close does not persist it again: the parked key is persisted once in total, and the rows keep it.
     */
    @Test
    void aParkPersistAnsweredOffTheRunsThreadStillSparesTheTrailASecondPersist() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, REWRITE, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 3));
        BlobKey stepBody = BlobKey.body(in.id(), 2);   // rewrite is step 2 (after the gate); the partner, step 3, parks it
        w.partner.failNext(100, () -> new NackException("order refused", false));
        w.gate.open();
        w.stepBodies.hold();
        try {
            Future<Integer> polled = w1.poll();
            until(() -> w.gate.entered() == 1, () -> "the run entered the gate");
            Thread run = w.gate.runs().get(0).thread;
            until(() -> w.stepBodies.held() == 1 && waitingInPark(run),
                    () -> "the park's persist held and the run waiting on it: held " + w.stepBodies.held() + ", " + run.getState() + " at "
                            + java.util.Arrays.toString(run.getStackTrace()));
            assertEquals(List.of(stepBody), w.stepBodies.persists(), "fixture: the park asked to persist the partner's input, the rewritten body");
            assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "no DLQ row while the persist is held");
            assertFalse(polled.isComplete(), "the round waits for its item: " + polled);

            w.stepBodies.release();   // the persist answers here, on the test thread: no trail is bound on it
            assertEquals(1, polled.await(5, SECONDS));
            until(() -> w.queue.claimsInFlight() == 0, () -> "claims answered");
        } finally {
            w.stepBodies.release();
        }
        assertEquals(1, w.partner.sent().size(), "fixture: the run reached the partner, which refused");
        assertEquals(stepBody, w.parkedRow().bodyKey(), "parked at the partner by the rewritten body");
        assertEquals(0, w.rows.size(), "parked: the item is completed");
        assertEquals(List.of(stepBody), w.stepBodies.persists(),
                "the parked key persisted once in total: the park marked it durable for the run's trail, whose close did not persist it again");
        Snapshot rewriteRow = w.store.timeline(in.id()).await().stream().filter(s -> "rewrite".equals(s.stepName())).findFirst().orElseThrow();
        assertEquals(Optional.of(stepBody), rewriteRow.bodyKey(), "the rewrite row keeps the parked key: it is durable");
        assertEquals(List.of(), logs.at(Level.WARNING, in.id()), "no persist failed, no row went metadata-only");
    }

    // --- the policy

    @Test
    void workerPolicyRefusesNonPositiveValues() {
        Duration second = Duration.ofSeconds(1);
        for (Duration bad : List.of(Duration.ZERO, Duration.ofNanos(-1))) {
            String lease = assertThrows(IllegalArgumentException.class, () -> new WorkerPolicy(bad, 5, second), "lease " + bad).getMessage();
            assertTrue(lease.contains("lease"), lease);
            String interval = assertThrows(IllegalArgumentException.class, () -> new WorkerPolicy(second, 5, bad), "pollInterval " + bad).getMessage();
            assertTrue(interval.contains("pollInterval"), interval);
        }
        for (int bad : new int[] {0, -1}) {
            String attempts = assertThrows(IllegalArgumentException.class, () -> new WorkerPolicy(second, bad, second), "maxAttempts " + bad).getMessage();
            assertTrue(attempts.contains("maxAttempts"), attempts);
        }
        WorkerPolicy smallest = new WorkerPolicy(Duration.ofNanos(1), 1, Duration.ofNanos(1));
        assertEquals(1, smallest.maxAttempts(), "the smallest positive values are a policy");
        assertEquals(new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofSeconds(1)), WorkerPolicy.DEFAULT);
    }

    @Test
    void theThreeArgumentWorkerUsesTheDefaultPolicy() {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = b.worker(def, NO_FILES, "w1");
        w.workers.add(w1);

        w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        assertEquals(WorkerPolicy.DEFAULT.lease(), w.queue.claims().get(0).lease, "leased for the default lease");
        assertEquals(WorkerPolicy.DEFAULT.lease().dividedBy(3), w.rt.timer().nextDelay(), "renewed at a third of the default lease");
        w1.start();
        until(() -> w.queue.claimsInFlight() == 0, () -> "start settled");
        assertEquals(WorkerPolicy.DEFAULT.pollInterval(), w.rt.timer().nextDelay(), "ticks at the default poll interval");
    }

    @Test
    void theFixedLeaseConstantIsGone() {
        assertThrows(NoSuchFieldException.class, () -> FlowWorker.class.getDeclaredField("LEASE"), "D-055: the lease is the policy's, renewed while the item runs");
    }

    // --- properties (jqwik): every try builds its own World; nothing lives on the instance

    sealed interface Move permits Advance, PollBy, Finish {}

    /** The clock moves {@code sixths} × lease / 6 — at most half a lease, while a renewal is due every third. */
    record Advance(int sixths) implements Move {}

    record PollBy(int worker) implements Move {}

    record Finish(int pick) implements Move {}

    @Provide
    Arbitrary<List<Move>> moves() {
        Arbitrary<Move> advance = Arbitraries.integers().between(1, 3).map(Advance::new);
        Arbitrary<Move> poll = Arbitraries.integers().between(0, 1).map(PollBy::new);
        Arbitrary<Move> finish = Arbitraries.integers().between(0, 7).map(Finish::new);
        return Arbitraries.frequencyOf(Tuple.of(3, advance), Tuple.of(3, poll), Tuple.of(2, finish)).list().ofMinSize(1).ofMaxSize(25);
    }

    @Property(tries = 60)
    void oneHolderPerItemUnderAnyInterleaving(@ForAll @IntRange(min = 1, max = 4) int messages, @ForAll("moves") List<Move> moves) throws Exception {
        World world = new World();
        try {
            FlowDefinition def = FlowDefinition.from(flow(2, List.of(RunGate.STEP, TO_PARTNER)));
            FlowBuilder b = world.builder();
            Delivery delivery = b.delivery(def, NO_FILES);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < messages; i++) ids.add(world.accept(delivery).id());
            List<FlowWorker> workers = List.of(world.worker(b, def, "w1", policy(LEASE, 1_000)), world.worker(b, def, "w2", policy(LEASE, 1_000)));

            for (Move m : moves) {
                switch (m) {
                    case Advance a -> world.rt.clock().advance(LEASE.multipliedBy(a.sixths()).dividedBy(6));
                    case PollBy p -> workers.get(p.worker()).poll();
                    case Finish f -> {
                        List<RunGate.Run> held = world.gate.held();
                        if (!held.isEmpty()) {
                            RunGate.Run run = held.get(f.pick() % held.size());
                            run.release();
                            run.joined();
                        }
                    }
                }
                world.settle();
                assertEquals(List.of(), world.gate.overlaps(), "two holders of one item after " + m);
            }
            world.gate.open();
            world.gate.joinPassed();
            world.drain(workers.get(0));
            world.gate.joinPassed();
            assertEquals(List.of(), world.gate.overlaps(), "two holders of one item while draining");
            assertEquals(0, world.rows.size(), "every item completed");
            for (String id : ids) assertTrue(world.sentIds().contains(id), id + " was sent: " + world.sentIds());
        } finally {
            world.close();
        }
    }

    sealed interface Trigger permits Tick, AcceptOne, ReleaseOne, SlowClaim, ClaimReturns {}

    /** One poll interval passes: the started worker's tick. */
    record Tick() implements Trigger {}

    /** A delivery through {@code pollingAfterAccept}: accepted, then a poll. */
    record AcceptOne() implements Trigger {}

    /** One held run ends: its item finished, it polls once. */
    record ReleaseOne(int pick) implements Trigger {}

    /** The next claim stays in flight until {@link ClaimReturns}. */
    record SlowClaim() implements Trigger {}

    record ClaimReturns() implements Trigger {}

    @Provide
    Arbitrary<List<Trigger>> triggers() {
        Arbitrary<Trigger> tick = Arbitraries.just(new Tick());
        Arbitrary<Trigger> accept = Arbitraries.just(new AcceptOne());
        Arbitrary<Trigger> release = Arbitraries.integers().between(0, 7).map(ReleaseOne::new);
        Arbitrary<Trigger> slow = Arbitraries.just(new SlowClaim());
        Arbitrary<Trigger> returns = Arbitraries.just(new ClaimReturns());
        return Arbitraries.frequencyOf(Tuple.of(3, tick), Tuple.of(3, accept), Tuple.of(3, release), Tuple.of(1, slow), Tuple.of(1, returns))
                .list().ofMinSize(1).ofMaxSize(30);
    }

    @Property(tries = 60)
    void runningItemsNeverExceedTheInFlightLimit(@ForAll @IntRange(min = 1, max = 3) int max, @ForAll("triggers") List<Trigger> triggers) throws Exception {
        World world = new World();
        try {
            FlowDefinition def = FlowDefinition.from(flow(max, List.of(RunGate.STEP, TO_PARTNER)));
            FlowBuilder b = world.builder();
            FlowWorker worker = world.worker(b, def, "w1", new WorkerPolicy(LEASE, 1_000, TICK));
            Function<Envelope, Future<Ack>> accepting = worker.pollingAfterAccept(b.delivery(def, NO_FILES));
            // running, as seen from outside: handed out to the worker and not yet past the gate (a released run is joined first)
            world.queue.probeRunning(() -> world.queue.handedOutTo("w1") - world.gate.exited());
            worker.start();
            world.settle();
            int accepted = 0;
            for (Trigger t : triggers) {
                switch (t) {
                    case Tick k -> world.rt.clock().advance(TICK);
                    case AcceptOne a -> {
                        if (accepted < 8) {
                            accepted++;
                            Envelope in = world.ingress();
                            assertEquals(new Ack.Accepted(in.id()), accepting.apply(in).await(5, SECONDS));
                        }
                    }
                    case ReleaseOne r -> {
                        List<RunGate.Run> held = world.gate.held();
                        if (!held.isEmpty()) {
                            RunGate.Run run = held.get(r.pick() % held.size());
                            run.release();
                            run.joined();
                        }
                    }
                    case SlowClaim s -> world.queue.holdNext();
                    case ClaimReturns c -> world.queue.releaseHeld();
                }
                world.settle();
                assertTrue(world.gate.peakActive() <= max, "running " + world.gate.peakActive() + " > max " + max + " after " + t);
            }
            for (RecordingWorkQueue.ClaimCall c : world.queue.claims()) {
                assertEquals(0, c.inFlightBefore, "a claim was issued while another was in flight: " + c);
                assertEquals(max - c.runningAtCall, c.max, "each claim asks for max − running: " + c);
            }
            assertTrue(world.queue.peakClaimsInFlight() <= 1, "claims in flight at once: " + world.queue.peakClaimsInFlight());
        } finally {
            world.close();
        }
    }
}
