package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Pipeline;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.api.Timer;
import io.flottila.core.admission.TargetLimit;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.node.FencedException;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-15b (D-101 (2), D-112, D-113 draft): a {@code to}'s send is bounded by the earlier of the message's deadline and the node's
 * fence. Under the permit the builder hands the producer a live {@link io.flottila.api.Deadline} —
 * {@code min(between(clock.instant(), ctx.deadline()), ofNanos(fence.openNanos()))}, both re-read at each {@code remaining()} —
 * and calls {@code send(e, d)}, never the plain {@code send(e)}; no time left: a retryable Nack "deadline passed", the producer
 * not called. With a {@link io.flottila.core.node.Fence} (only then) a worker's run stops waiting for a send still open when the
 * fence closes: a timer on the Runtime's timer at {@code openNanos} re-reads it — still open (renewed): wait on; closed: the run's
 * wait fails with {@link FencedException}, never retried, never parked, the item left unsettled and its rerun in doubt; the
 * abandoned send keeps its target permit until its own future settles. A producer failure landing while the fence is closed is
 * a {@link FencedException} with that failure as its cause. A run without a {@code Fence} (sync, at-most-once, a worker fenced by a
 * plain {@code BooleanSupplier}) is bounded by the message's deadline only.
 *
 * <p>The API assumed (packet P2-15b): {@code io.flottila.api.Deadline}, {@code Producer.send(Envelope, Deadline)},
 * {@code io.flottila.core.node.Fence extends BooleanSupplier { long openNanos(); }}; {@code FlowBuilder.fence(BooleanSupplier)}
 * unchanged — a {@code Fence} argument also bounds the sends. The partner ({@link DeadlinePartner}) is a component at the SPI
 * boundary; the fence ({@link ClockFence}) runs on the fake clock.
 */
@Timeout(60)
class FlowBuilderSendDeadlineTest {

    static final Instant T0 = FakeRuntime.T0;
    static final Duration LEASE = Duration.ofSeconds(30);   // the item lease
    static final Map<String, Object> PROBE = Map.of("type", "probe", "name", "probe");
    static final Map<String, Object> TO = Map.of("type", "to", "name", "partner", "endpoint", "partner", "retryable", true);
    static final Map<String, Object> TICK = Map.of("type", "tick", "name", "slow");
    /** What {@link #TICK} takes of the message's time. */
    static final Duration TICKS = Duration.ofMillis(500);
    static final Map<String, EndpointConfig> ENDPOINTS = Map.of("partner", new EndpointConfig("partner", URI.create("partner://p:1/in"), Map.of()));

    final List<World> worlds = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (World w : worlds) {
            w.partner.replyAll();   // no worker stop waits on a held send
            w.workers.forEach(FlowWorker::stop);
        }
    }

    /** A flow {@code orders} in {@code mode} whose {@code delivery.timeout} is {@code timeout}; 3 attempts 1 s apart; 4 in flight. */
    static FlowDefinition flow(String mode, String timeout, List<Map<String, Object>> steps) {
        Map<String, Object> doc = DeliveryTest.flow(mode, steps);
        doc.put("delivery", Map.of("mode", mode, "timeout", timeout));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("retry", Map.of("attempts", 3, "initial", "1s", "max", "1s"));
        return FlowDefinition.from(doc);
    }

    /** One node's world: partner, store, queue, DLQ and the target limits, over one fake runtime. */
    final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final DeadlinePartner partner = new DeadlinePartner();
        final InMemoryMessageStore store = new InMemoryMessageStore();
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
        final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        final InMemoryDlq dlq = new InMemoryDlq();
        final Targets targets;
        /** What the {@code to} threw at the probe step before it (after its retries). */
        final List<RuntimeException> seen = new CopyOnWriteArrayList<>();
        final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
        /** Schedules of the flow's retry backoff (1 s) on the Runtime's timer, counted once pending. */
        final AtomicInteger backoffs = new AtomicInteger();
        final Runtime runtime;

        World(int maxCalls) {
            rt.withComponent(partner);
            Runtime r = rt.runtime();
            Timer noting = (delay, task) -> {
                Timer.Cancellable c = rt.timer().schedule(delay, task);
                if (delay.equals(Duration.ofSeconds(1))) backoffs.incrementAndGet();   // after the schedule: a backoff seen is pending
                return c;
            };
            runtime = new Runtime(r.clock(), noting, r.ids(), r.blobs(), r.hasher(), r.components());
            targets = new Targets(new Targets.Limit(maxCalls, Duration.ofSeconds(5)), Map.of(), rt.timer());
            worlds.add(this);
        }

        FlowBuilder builder(BooleanSupplier fence) {
            StepFactory probe = ParkingTest.factory("probe", s -> (e, rest) -> {
                try {
                    return rest.apply(e);
                } catch (RuntimeException failure) {
                    seen.add(failure);
                    throw failure;
                }
            });
            StepFactory tick = ParkingTest.factory("tick", s -> Stage.of(e -> { rt.clock().advance(TICKS); return e; }));
            return new FlowBuilder(runtime, List.of(probe, tick), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                    .recording(store, Meter.system()).queue(queue).dlq(dlq).targets(targets).fence(fence);
        }

        FlowWorker worker(FlowBuilder b, FlowDefinition def) {
            FlowWorker w = b.worker(def, NO_FILES, "n1", FlowWorkerTest.policy(LEASE, 5));
            workers.add(w);
            return w;
        }

        Envelope message() {
            return rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
        }

        Envelope accept(Delivery delivery) throws Exception {
            Envelope in = message();
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(5, SECONDS));
            return in;
        }

        DeadlinePartner.Call awaitCall(int n) {
            until(() -> partner.calls.size() >= n, () -> "send " + n + " reached the partner: " + partner.calls + " (plain " + partner.plainCalls + ")");
            return partner.call(n - 1);
        }

        /** Moves the fake clock to {@code T0 + t}, stopping at every timer task due on the way so each fires at its own instant. */
        void to(Duration t) {
            Instant target = T0.plus(t);
            if (target.isBefore(rt.clock().instant())) fail("the test moves the clock back to " + target + " from " + rt.clock().instant());
            while (rt.timer().pending() > 0 && !rt.clock().instant().plus(rt.timer().nextDelay()).isAfter(target)) rt.clock().advance(rt.timer().nextDelay());
            rt.clock().set(target);
        }

        List<Parked> dlqRows() { return dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(); }

        TargetLimit limit() { return targets.of(DeadlinePartner.TARGET); }
    }

    @Test
    void aSendIsBoundedByTheEarlierOfTheMessageDeadlineAndTheFence() throws Exception {
        World w = new World(16);
        ClockFence fence = new ClockFence(w.rt.clock(), T0.plusMillis(7_250));
        FlowBuilder b = w.builder(fence);
        FlowDefinition def = flow("at-least-once", "30s", List.of(PROBE, TO));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = w.worker(b, def);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = w.awaitCall(1);
        assertEquals(in.id(), call.envelope().id());
        assertEquals(Duration.ofMillis(7_250), call.remaining(), "the fence ends first: 7.25 s of it left, 30 s of the message");
        w.to(Duration.ofSeconds(1));
        assertEquals(Duration.ofMillis(6_250), call.remaining(), "re-read a second later: live, not the value at the call");
        fence.renewTo(T0.plusSeconds(60));
        assertEquals(Duration.ofSeconds(29), call.remaining(), "the fence renewed past the message's deadline: the message's 29 s left");
        call.reply();
        assertEquals(1, round.await(10, SECONDS));
        until(() -> w.queue.completes().size() == 1, () -> "the item completed: " + w.queue.completeCalls());

        // the message's deadline first: a flow of 4.5 s under a fence open for a minute more
        FlowDefinition shortFlow = flow("at-least-once", "4500ms", List.of(PROBE, TO));
        Envelope quick = w.accept(b.delivery(shortFlow, NO_FILES));
        FlowWorker quickWorker = w.worker(b, shortFlow);
        Future<Integer> quickRound = quickWorker.poll();
        DeadlinePartner.Call second = w.awaitCall(2);
        assertEquals(quick.id(), second.envelope().id());
        assertEquals(Duration.ofMillis(4_500), second.remaining(), "the message's 4.5 s, earlier than the fence's 59 s");
        second.reply();
        assertEquals(1, quickRound.await(10, SECONDS));
        assertEquals(List.of(), w.partner.plainCalls, "a bounded send is send(e, d), never the plain send(e)");
        assertEquals(List.of(), w.seen);
    }

    @Test
    void aRenewedFenceMovesTheDeadlineAndTheSendCompletes() throws Exception {
        World w = new World(16);
        ClockFence fence = new ClockFence(w.rt.clock(), T0.plusSeconds(10));
        FlowBuilder b = w.builder(fence);
        FlowDefinition def = flow("at-least-once", "30s", List.of(PROBE, TO));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = w.worker(b, def);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = w.awaitCall(1);
        w.to(Duration.ofSeconds(8));
        assertEquals(Duration.ofSeconds(2), call.remaining(), "fixture: 2 s of the fence left");
        fence.renewTo(T0.plusSeconds(20));   // the node renewed its lease
        assertEquals(Duration.ofSeconds(12), call.remaining(), "the renewal moved the send's deadline");
        w.to(Duration.ofSeconds(11));         // past the fence's first end: re-read there, still open, the run waits on
        assertEquals(Duration.ofSeconds(9), call.remaining());

        call.reply();
        assertEquals(1, round.await(10, SECONDS));
        until(() -> w.queue.completes().size() == 1,
                () -> "the send completed and the item with it — not abandoned at the fence's first end: " + w.queue.completeCalls() + ", seen " + w.seen);
        assertEquals(List.of(), w.seen, "nothing thrown at the step before the send");
        assertEquals(0, w.rows.size());
        assertEquals(List.of(in.id()), w.partner.calls.stream().map(c -> c.envelope().id()).toList(), "sent once");
    }

    @Test
    void aSendStillOpenAtTheFenceIsAbandonedFencedAndRerunInDoubt() throws Exception {
        World w = new World(16);
        ClockFence fence = new ClockFence(w.rt.clock(), T0.plusSeconds(10));
        FlowBuilder b = w.builder(fence);
        FlowDefinition def = flow("at-least-once", "30s", List.of(PROBE, TO));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = w.worker(b, def);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = w.awaitCall(1);
        w.to(Duration.ofSeconds(10));   // the fence closes; the partner has not answered
        assertEquals(1, round.await(10, SECONDS), "the run stopped waiting at the fence: the round ends though the send never answered");
        assertEquals(1, w.seen.size(), "the send's step failed once: " + w.seen);
        assertInstanceOf(FencedException.class, w.seen.get(0), "abandoned at the fence: fenced, " + w.seen.get(0));
        assertEquals(1, w.partner.calls.size(), "not retried, though the step is retryable with 3 attempts");
        assertEquals(List.of(), w.queue.completeCalls(), "not completed");
        assertEquals(List.of(), w.queue.holds(), "nor held");
        assertEquals(List.of(), w.dlqRows(), "nor parked");
        assertEquals(1, w.rows.size(), "the item left unsettled for whoever runs it next");

        call.reply();   // the partner answers late
        assertEquals(1, w.rows.size(), "a late answer settles nothing");
        assertEquals(List.of(), w.queue.completeCalls());

        fence.renewTo(T0.plusSeconds(600));
        w.to(Duration.ofSeconds(10).plus(LEASE).plusSeconds(1));   // past the item's lease: the worker's lease-end wake claims it (D-121 A6), no poll of the test
        DeadlinePartner.Call again = w.awaitCall(2);
        assertEquals(in.id(), again.envelope().id(), "the rerun sends under the same message id");
        List<WorkQueue.Item> handed = w.queue.claimsBy("n1").stream().filter(c -> c.items != null).flatMap(c -> c.items.stream()).toList();
        assertEquals(2, handed.size(), "handed out twice: " + w.queue.claims());
        assertEquals(1, handed.get(1).doubts(), "the rerun is in doubt: the abandoned run left its item unsettled");
        assertEquals(2, handed.get(1).attempts());
        again.reply();
        until(() -> w.rows.size() == 0 && worker.unsettled() == 0, () -> "the rerun completes the item: " + w.rows.snapshot());
        assertEquals(List.of(), w.dlqRows());
    }

    @Test
    void anAbandonedSendHoldsItsPermitUntilItSettles() throws Exception {
        World w = new World(1);   // one call at a time to the partner's target
        ClockFence fence = new ClockFence(w.rt.clock(), T0.plusSeconds(10));
        FlowBuilder b = w.builder(fence);
        FlowDefinition def = flow("at-least-once", "30s", List.of(PROBE, TO));
        w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = w.worker(b, def);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call abandoned = w.awaitCall(1);
        w.to(Duration.ofSeconds(10));
        assertEquals(1, round.await(10, SECONDS));
        assertInstanceOf(FencedException.class, w.seen.get(0), "fixture: the send was abandoned at the fence");
        assertEquals(1, w.limit().gauge().inFlight(), "the abandoned send keeps its permit: its request may still be out at the partner");

        Pipeline sync = b.build(flow("sync", "30s", List.of(TO)), NO_FILES);
        Envelope other = w.message();
        CompletableFuture<Envelope> running = FlowBuilderTargetTest.run(sync, other);
        until(() -> w.limit().gauge().waiting() == 1, () -> "another send to the target waits for the permit: " + w.limit().gauge());
        assertEquals(1, w.partner.calls.size(), "nothing of the waiting send reached the partner");

        abandoned.reply();   // the abandoned send settles at last
        DeadlinePartner.Call next = w.awaitCall(2);
        assertEquals(other.id(), next.envelope().id(), "the permit passed to the waiting send once the abandoned one settled");
        next.reply();
        assertEquals(other.id(), running.get(5, SECONDS).id());
        assertEquals(0, w.limit().gauge().inFlight(), "both permits given back");
        assertEquals(1, w.rows.size(), "the abandoned item stays unsettled whatever its send answered late");
    }

    @Test
    void aProducerFailureLandingWhileTheFenceIsClosedIsFenced() throws Exception {
        World w = new World(16);
        ClockFence fence = new ClockFence(w.rt.clock(), T0.plusSeconds(60));
        FlowBuilder b = w.builder(fence);
        FlowDefinition def = flow("at-least-once", "30s", List.of(PROBE, TO));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = w.worker(b, def);

        // witness: with the fence open the same failure is a failed attempt, retried after the backoff
        Envelope first = w.accept(delivery);
        Future<Integer> round = worker.poll();
        w.awaitCall(1).fail(new NackException("partner busy", true));
        until(() -> w.backoffs.get() == 1, () -> "the retry's backoff armed: " + w.partner.calls);
        w.to(Duration.ofSeconds(1));
        DeadlinePartner.Call retried = w.awaitCall(2);
        assertEquals(first.id(), retried.envelope().id(), "fixture: the open fence lets a retryable failure be retried");
        retried.reply();
        assertEquals(1, round.await(10, SECONDS));
        until(() -> w.queue.completes().size() == 1, () -> "the first item completed: " + w.queue.completeCalls());
        assertEquals(List.of(), w.seen);

        Envelope second = w.accept(delivery);
        Future<Integer> fenced = worker.poll();
        DeadlinePartner.Call call = w.awaitCall(3);
        assertEquals(second.id(), call.envelope().id());
        fence.supersede();   // the fence closes at once, its timer not due yet
        NackException busy = new NackException("partner busy", true);
        call.fail(busy);
        assertEquals(1, fenced.await(10, SECONDS), "the round ends at once, the clock unmoved: no retry backoff waited");
        assertEquals(1, w.seen.size(), "the send's step failed once: " + w.seen);
        FencedException f = assertInstanceOf(FencedException.class, w.seen.get(0), "a failure landing while the fence is closed is fenced: " + w.seen.get(0));
        assertSame(busy, f.getCause(), "carrying the producer's failure as its cause");
        assertEquals(3, w.partner.calls.size(), "not retried");
        assertEquals(1, w.backoffs.get(), "no backoff armed for it");
        assertEquals(List.of(), w.dlqRows(), "not parked");
        assertEquals(1, w.queue.completes().size(), "the second item not completed");
        assertEquals(1, w.rows.size(), "the second item left unsettled");
    }

    @Test
    void aSendPastTheMessageDeadlineIsNotStarted() throws Exception {
        World w = new World(16);
        FlowBuilder b = w.builder(new ClockFence(w.rt.clock(), T0.plusSeconds(600)));
        // not retryable: a retryable to would wait out the flow's 1 s backoff on the fake timer before failing again (Retry does
        // not read the deadline, D-100 "nothing cancels at the deadline yet"); the case is the send's own refusal
        Map<String, Object> once = Map.of("type", "to", "name", "partner", "endpoint", "partner");
        Pipeline late = b.build(flow("sync", "500ms", List.of(TICK, once)), NO_FILES);   // the step before takes all of its 500 ms

        Throwable failure = FlowBuilderTargetTest.failureOf(FlowBuilderTargetTest.run(late, w.message()));
        NackException nack = assertInstanceOf(NackException.class, failure, "past the deadline the send fails with a Nack: " + failure);
        assertTrue(nack.retryable(), "retryable: a rerun gets fresh time: " + nack);
        assertTrue(nack.reason().contains("deadline passed"), nack.reason());
        assertEquals(List.of(), w.partner.calls, "send(e, d) never called with no time left");
        assertEquals(List.of(), w.partner.plainCalls, "nor send(e)");
        assertEquals(0, w.limit().gauge().inFlight(), "the permit given back");

        // witness: 1 ms left is time left — the send starts and sees exactly that
        Pipeline justInTime = b.build(flow("sync", "501ms", List.of(TICK, once)), NO_FILES);
        Envelope in = w.message();
        CompletableFuture<Envelope> running = FlowBuilderTargetTest.run(justInTime, in);
        DeadlinePartner.Call call = w.awaitCall(1);
        assertEquals(in.id(), call.envelope().id());
        assertEquals(Duration.ofMillis(1), call.remaining(), "501 ms - 500 ms");
        call.reply();
        assertEquals(in.id(), running.get(5, SECONDS).id());
    }

    @Test
    void unfencedRunsAreBoundedByTheMessageDeadlineOnly() throws Exception {
        // a sync run is never fenced: under a Fence of 2 s its send sees the message's 30 s and is not abandoned at the fence
        World w = new World(16);
        FlowBuilder b = w.builder(new ClockFence(w.rt.clock(), T0.plusSeconds(2)));
        Delivery sync = b.delivery(flow("sync", "30s", List.of(TO)), NO_FILES);
        Envelope s = w.message();
        CompletableFuture<Future<Ack>> answered = DeliveryTest.applyOnVirtualThread(sync, s);
        DeadlinePartner.Call call = w.awaitCall(1);
        assertEquals(Duration.ofSeconds(30), call.remaining(), "the message's 30 s, not the fence's 2 s");
        w.to(Duration.ofSeconds(3));
        assertEquals(Duration.ofSeconds(27), call.remaining(), "past the fence: still the message's time");
        call.reply();
        Ack ack = answered.get(5, SECONDS).await(5, SECONDS);
        assertInstanceOf(Ack.Reply.class, ack, "the sync run waited for its reply past the fence: " + ack);

        // a worker fenced by a plain BooleanSupplier: the message's deadline only, and nothing abandons its send
        World u = new World(16);
        FlowBuilder ub = u.builder(() -> true);
        FlowDefinition def = flow("at-least-once", "20s", List.of(PROBE, TO));
        u.accept(ub.delivery(def, NO_FILES));
        FlowWorker worker = u.worker(ub, def);
        Future<Integer> round = worker.poll();
        DeadlinePartner.Call held = u.awaitCall(1);
        assertEquals(Duration.ofSeconds(20), held.remaining(), "the message's 20 s");
        u.to(Duration.ofSeconds(21));
        assertEquals(Duration.ofSeconds(-1), held.remaining(), "past the message's deadline: 1 s overdue, for the producer to cut");
        held.reply();
        assertEquals(1, round.await(10, SECONDS));
        until(() -> u.queue.completes().size() == 1, () -> "the run waited for the reply and completed the item: " + u.queue.completeCalls() + ", seen " + u.seen);
        assertEquals(List.of(), u.seen, "nothing abandoned");
    }
}
