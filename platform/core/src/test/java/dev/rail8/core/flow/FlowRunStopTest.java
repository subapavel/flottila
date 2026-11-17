package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.NackException;
import dev.rail8.api.Stage;
import dev.rail8.api.StepFactory;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.InMemoryFailedMessages;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Page;
import dev.rail8.core.record.Snapshot;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.rail8.core.flow.FlowWorkerTest.NO_FILES;
import static dev.rail8.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run stops when its time limit passes or an operator cancels its message, on a fake clock: the history row at the step where
 * it stopped says why (and who), an at-least-once run its time limit stopped is a failed attempt that is deferred, a sync one
 * answers a retryable Nack, and a cancelled message ends — completed only after its send settled, never retried, never
 * parked. The partner ({@link DeadlinePartner}) leaves every send open until the case answers it, as a producer that cuts its
 * send at the deadline would answer it then.
 */
@Timeout(60)
class FlowRunStopTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Map<String, Object> PROBE = Map.of("type", "probe", "name", "probe");
    static final Map<String, Object> TO = Map.of("type", "to", "name", "partner", "endpoint", "partner");
    static final Map<String, EndpointConfig> ENDPOINTS = Map.of("partner", new EndpointConfig("partner", URI.create("partner://p:1/in"), Map.of()));
    static final FailedMessages.Actor ALICE = new FailedMessages.Actor("alice", "127.0.0.1");

    final FakeRuntime rt = FakeRuntime.create();
    final DeadlinePartner partner = new DeadlinePartner();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock(), dlq);
    final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
    final FailedMessages failed = new InMemoryFailedMessages(rows);
    final Runs runs = new Runs(rt.clock(), rt.timer(), LEASE);
    final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
    /** The thread the {@code probe} step last ran on: the run's. */
    final AtomicReference<Thread> runThread = new AtomicReference<>();
    /** What the next run of the {@code flaky} step does before it fails; counted. */
    final AtomicInteger flakyRuns = new AtomicInteger();
    /** Run once by the next read of a message's history, before it answers: the worker's read before its run. */
    final AtomicReference<Runnable> onTimeline = new AtomicReference<>();
    /** {@link #store}, read through {@link #onTimeline}. */
    final MessageStore history = new MessageStore() {
        @Override public Future<Void> record(Snapshot snapshot) { return store.record(snapshot); }

        @Override public Future<List<Snapshot>> timeline(String messageId) {
            Runnable first = onTimeline.getAndSet(null);
            if (first != null) first.run();
            return store.timeline(messageId);
        }

        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            return store.findByKey(name, value, after, limit);
        }
    };

    FlowRunStopTest() {
        rt.withComponent(partner);
    }

    @AfterEach
    void tearDown() {
        partner.replyAll();   // no worker stop waits on a held send
        workers.forEach(FlowWorker::stop);
    }

    /** A flow {@code orders} in {@code mode} whose runs have {@code timeout}; 4 in flight. */
    static FlowDefinition flow(String mode, String timeout, List<Map<String, Object>> steps) {
        Map<String, Object> doc = DeliveryTest.flow(mode, steps);
        doc.put("delivery", new HashMap<>(Map.of("mode", mode, "timeout", timeout)));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        return FlowDefinition.from(doc);
    }

    FlowBuilder builder() {
        StepFactory probe = ParkingTest.factory("probe", s -> Stage.of(e -> {
            runThread.set(Thread.currentThread());
            return e;
        }));
        // stops its own run with a cancel, clears the interrupt as a library that swallows it would, then fails as if it may get better
        StepFactory flaky = ParkingTest.factory("flaky", s -> Stage.of(e -> {
            flakyRuns.incrementAndGet();
            runs.stop(e.id(), new Runs.Cancelled("alice", Optional.empty()));
            Thread.interrupted();
            throw new NackException("partner busy", true);
        }));
        return new FlowBuilder(rt.runtime(), List.of(probe, flaky), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(history, Meter.system()).queue(queue).dlq(dlq).runs(runs);
    }

    /** {@code b}'s worker {@code n1} of {@code def}, bounded to {@code maxAttempts} hand-outs an hour apart. */
    FlowWorker worker(FlowBuilder b, FlowDefinition def, int maxAttempts) {
        FlowWorker w = FlowWorkerTest.policy(LEASE, maxAttempts).worker(b, def, "n1");
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
        until(() -> partner.calls.size() >= n, () -> "send " + n + " reached the partner: " + partner.calls);
        return partner.call(n - 1);
    }

    /** The rows of {@code id}'s history that failed. */
    List<Snapshot> failures(String id) {
        return store.timeline(id).await().stream().filter(s -> s.outcome() == Snapshot.Outcome.FAILED).toList();
    }

    /** Waits until the run's thread waits for its open sends: it stopped and left its steps. */
    void awaitWaitingForSends() {
        until(() -> runThread.get() != null && Arrays.stream(runThread.get().getStackTrace()).anyMatch(f -> f.getMethodName().equals("awaitSends")),
                () -> "the run waits for its sends: " + (runThread.get() == null ? "no run" : Arrays.toString(runThread.get().getStackTrace())));
    }

    List<Parked> dlqRows() {
        return dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
    }

    @Test
    void aTimeLimitDuringASendStopsTheRunWhichIsDeferredWithItsReasonInTheHistory() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "10s", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = awaitCall(1);
        rt.clock().advance(Duration.ofSeconds(10));
        awaitWaitingForSends();
        assertEquals(1, rows.size(), "the run waits for its send before its item is settled");
        assertTrue(queue.deferCalls().isEmpty(), "nothing deferred while the send is out");
        call.fail(new NackException("cut at its deadline", true));   // what a producer does at the deadline

        assertEquals(1, round.await(10, SECONDS));
        until(() -> queue.deferCalls().size() == 1, () -> "the run deferred: " + queue.deferCalls());
        assertEquals(Boolean.TRUE, queue.deferCalls().getFirst().answer(), "a failed attempt that may get better: deferred");
        List<Snapshot> stopped = failures(in.id());
        assertEquals(1, stopped.size(), "one failed row: " + stopped);
        assertEquals(2, stopped.getFirst().step(), "at the step where the run stopped, the send");
        assertTrue(stopped.getFirst().error().orElseThrow().contains("its time limit of PT10S passed"), stopped.getFirst().error().orElseThrow());
        assertEquals(1, partner.calls.size(), "nothing sent after the stop");
        assertTrue(dlqRows().isEmpty(), "not parked: retries are left");
    }

    @Test
    void aTimeLimitPassingAfterTheLastStepReturnedKeepsTheDelivery() throws Exception {
        StepFactory late = ParkingTest.factory("late", s -> Stage.of(e -> {
            rt.clock().advance(Duration.ofSeconds(10));   // the time limit passes on this thread, after the send returned
            return e;
        }));
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(late), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(store, Meter.system()).queue(queue).dlq(dlq).runs(runs);
        FlowDefinition def = flow("at-least-once", "10s", List.of(TO, Map.of("type", "late", "name", "late")));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);

        Future<Integer> round = worker.poll();
        awaitCall(1).reply();
        assertEquals(1, round.await(10, SECONDS));
        until(() -> rows.size() == 0, () -> "the item completed: " + rows.snapshot());
        assertTrue(queue.deferCalls().isEmpty(), "delivered, not deferred: " + queue.deferCalls());
        assertTrue(failures(in.id()).isEmpty(), "no failed row: " + failures(in.id()));
        assertEquals(1, partner.calls.size());
    }

    @Test
    void aCancelDuringASendEndsTheMessageOnlyOnceTheSendSettled() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = awaitCall(1);
        FailedMessages.Stopping stopping = assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.of("wrong order")).await());
        assertEquals(Optional.of("n1"), stopping.node(), "the holder named");
        assertTrue(runs.stop(in.id(), new Runs.Cancelled("alice", Optional.of("wrong order"))), "the run on this node stopped at once");
        awaitWaitingForSends();
        assertEquals(1, rows.size(), "the key does not move on while the send is out");
        call.reply();   // the partner answers late: the message is cancelled all the same

        assertEquals(1, round.await(10, SECONDS));
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertTrue(queue.deferCalls().isEmpty(), "never retried");
        assertTrue(dlqRows().isEmpty(), "never parked");
        List<Snapshot> stopped = failures(in.id());
        assertEquals(1, stopped.size(), "one failed row: " + stopped);
        assertEquals(2, stopped.getFirst().step());
        assertTrue(stopped.getFirst().error().orElseThrow().contains("cancelled by alice: wrong order"), stopped.getFirst().error().orElseThrow());
    }

    @Test
    void aCancelledWaitingMessageIsDueAtOnceAndEndsAtItsFirstStep() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);
        Future<Integer> first = worker.poll();
        awaitCall(1).fail(new NackException("partner down", true));
        assertEquals(1, first.await(10, SECONDS));
        until(() -> queue.deferCalls().size() == 1, () -> "deferred an hour: " + queue.deferCalls());

        FailedMessages.Stopping stopping = assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.of("wrong order")).await());
        assertEquals(Optional.empty(), stopping.node(), "no one holds it");
        FailedMessages.Stopping again = assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), new FailedMessages.Actor("bob", "::1"), Optional.empty()).await());
        assertEquals(stopping.event(), again.event(), "a second cancel answers the first one's event");

        assertEquals(1, worker.poll().await(10, SECONDS), "due at once, not in an hour");
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertEquals(1, partner.calls.size(), "nothing of the flow ran again");
        List<Snapshot> stopped = failures(in.id());
        assertEquals(2, stopped.size(), "the first attempt's failure and the cancel: " + stopped);
        assertEquals(1, stopped.getLast().step(), "at the first step");
        assertTrue(stopped.getLast().error().orElseThrow().contains("cancelled by alice: wrong order"), stopped.getLast().error().orElseThrow());
        assertTrue(dlqRows().isEmpty(), "never parked");
    }

    @Test
    void aCancelTakenOnAnotherNodeStopsTheRunAtItsNextRenewal() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = awaitCall(1);
        assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.empty()).await());   // no stop on this node
        rt.clock().advance(LEASE.dividedBy(3));   // the lease's renewal tells of the mark
        awaitWaitingForSends();
        call.fail(new NackException("cut", true));

        assertEquals(1, round.await(10, SECONDS));
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertTrue(queue.deferCalls().isEmpty(), "never retried");
        List<Snapshot> stopped = failures(in.id());
        assertEquals(1, stopped.size(), "one failed row: " + stopped);
        assertTrue(stopped.getFirst().error().orElseThrow().contains("cancelled by alice"), stopped.getFirst().error().orElseThrow());
    }

    /**
     * A cancel the lease's renewal tells of while the worker still reads the message's history, before its run is registered, stops
     * the run from its start: nothing of the flow runs, and its first step records who cancelled it.
     */
    @Test
    void aCancelLearntBeforeTheRunStartedStopsItFromItsStart() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);
        onTimeline.set(() -> {
            assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.of("wrong order")).await());   // on another node
            rt.clock().advance(LEASE.dividedBy(3));   // the renewal tells of the mark: no run to stop yet
        });

        assertEquals(1, worker.poll().await(10, SECONDS));
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertEquals(null, runThread.get(), "no step of the flow ran");
        assertTrue(partner.calls.isEmpty(), "nothing sent");
        List<Snapshot> stopped = failures(in.id());
        assertEquals(1, stopped.size(), "one failed row: " + stopped);
        assertEquals(1, stopped.getFirst().step(), "at the first step");
        assertTrue(stopped.getFirst().error().orElseThrow().contains("cancelled by alice: wrong order"), stopped.getFirst().error().orElseThrow());
        assertTrue(dlqRows().isEmpty(), "never parked");
    }

    /**
     * A run that fails on its last allowed attempt after a cancel on another node marked its item, before any renewal told of it,
     * is not parked: the worker asks the queue for the mark before it parks a message that has no key, and the message ends.
     */
    @Test
    void aLastAttemptFailingBeforeItsRenewalToldOfTheCancelEndsAndIsNotParked() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 1);

        Future<Integer> round = worker.poll();
        DeadlinePartner.Call call = awaitCall(1);
        assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.empty()).await());   // on another node: no stop here
        call.fail(new NackException("partner down", true));

        assertEquals(1, round.await(10, SECONDS));
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertTrue(dlqRows().isEmpty(), "ended, not parked: " + dlqRows());
        assertTrue(queue.deferCalls().isEmpty(), "never retried");
    }

    /**
     * A message past its bound whose cancel lands while the worker reads its history is not parked: the run starts stopped instead,
     * so its first step records the cancel, and the message ends.
     */
    @Test
    void aMessagePastItsBoundCancelledWhileItsHistoryIsReadEndsAndIsNotParked() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 1);
        assertEquals(1, rows.claim("gone", "orders", 1, LEASE).await().size(), "a holder that died with its hand-out");
        rt.clock().advance(LEASE.plusSeconds(1));
        onTimeline.set(() -> assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.of("not needed")).await()));

        assertEquals(1, worker.poll().await(10, SECONDS), "its second hand-out, past its bound of one");
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertTrue(dlqRows().isEmpty(), "ended, not parked: " + dlqRows());
        assertTrue(partner.calls.isEmpty(), "nothing of the flow ran");
        List<Snapshot> stopped = failures(in.id());
        assertEquals(1, stopped.size(), "one failed row: " + stopped);
        assertEquals(1, stopped.getFirst().step(), "at the first step");
        assertTrue(stopped.getFirst().error().orElseThrow().contains("cancelled by alice: not needed"), stopped.getFirst().error().orElseThrow());
    }

    @Test
    void aStepRetryNeitherWaitsNorTriesAgainOnceItsRunStopped() throws Exception {
        FlowBuilder b = builder();
        Map<String, Object> flaky = Map.of("type", "flaky", "name", "flaky", "retryable", true);
        Map<String, Object> doc = DeliveryTest.flow("at-least-once", List.of(flaky, TO));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("retry", Map.of("attempts", 3, "initial", "1s", "max", "1s"));
        FlowDefinition def = FlowDefinition.from(doc);
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 3);

        assertEquals(1, worker.poll().await(10, SECONDS), "no pause on the timer: the round ends without the clock moving");
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertEquals(1, flakyRuns.get(), "one attempt, no retry after the stop");
        assertTrue(partner.calls.isEmpty());
        assertTrue(failures(in.id()).getFirst().error().orElseThrow().contains("cancelled by alice"));
    }

    @Test
    void aCancelledMessagePastItsBoundEndsAndIsNotParked() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", "1h", List.of(PROBE, TO));
        Envelope in = accept(b.delivery(def, NO_FILES));
        FlowWorker worker = worker(b, def, 1);
        assertEquals(1, rows.claim("gone", "orders", 1, LEASE).await().size(), "a holder that died with its hand-out");
        rt.clock().advance(LEASE.plusSeconds(1));
        assertInstanceOf(FailedMessages.Stopping.class, failed.end(in.id(), ALICE, Optional.of("not needed")).await());

        assertEquals(1, worker.poll().await(10, SECONDS), "its second hand-out, past its bound of one");
        until(() -> rows.size() == 0, () -> "the item ended: " + rows.snapshot());
        assertTrue(dlqRows().isEmpty(), "ended, not parked: " + dlqRows());
        assertTrue(partner.calls.isEmpty(), "nothing of the flow ran");
        assertEquals(1, failures(in.id()).size());
        assertEquals(1, failures(in.id()).getFirst().step());
    }

    @Test
    void aSyncRunPastItsTimeLimitAnswersARetryableNack() throws Exception {
        FlowBuilder b = builder();
        Delivery delivery = b.delivery(flow("sync", "5s", List.of(PROBE, TO)), NO_FILES);
        Envelope in = message();
        CompletableFuture<Ack> answer = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                answer.complete(delivery.apply(in).await(30, SECONDS));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        });
        DeadlinePartner.Call call = awaitCall(1);
        rt.clock().advance(Duration.ofSeconds(5));
        awaitWaitingForSends();
        call.fail(new NackException("cut at its deadline", true));

        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, answer.get(10, SECONDS));
        assertTrue(nack.retryable(), "worth trying again");
        assertTrue(nack.reason().contains("its time limit of PT5S passed"), nack.reason());
        until(() -> failures(in.id()).size() == 1, () -> "the failed row written: " + store.timeline(in.id()).await());
        assertEquals(2, failures(in.id()).getFirst().step(), "at the send");
    }
}
