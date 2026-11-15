package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-16b (D-121 (b) B4 (i)) in core: a worker's same-key fast path that took nothing and was told the node of the key's next row
 * announces that row's flow to that node through the signals its builder was given ({@link FlowBuilder#signals}); a fast path
 * told no node, or one that took the next row, announces nothing. The queue is an in-memory one behind {@link NodeBound}, which
 * answers its fast paths as a queue of node {@code n-a} answers for a key whose next row lies on {@code n-b} (the store boundary);
 * the signals record what they are told (the SPI boundary).
 */
@Timeout(30)
class FlowWorkerNextNodeTest {

    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final RunGate gate = new RunGate();
    final List<String> announced = new CopyOnWriteArrayList<>();
    final WorkSignals signals = (target, flow) -> announced.add(target + " " + flow);
    FlowWorker worker;

    @AfterEach
    void tearDown() {
        if (worker != null) worker.stop();
        gate.open();
    }

    /** {@code rows}, its fast path's "nothing taken" answered as the next row lying on {@code nextNode} (null: as it is). */
    record NodeBound(InMemoryWorkQueue rows, String nextNode) implements WorkQueue {
        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return rows.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return rows.claim(workerId, flow, max, lease); }
        @Override public Future<Boolean> complete(long seq, int attempts) { return rows.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return rows.completeAndTakeNext(seq, attempts, workerId, lease)
                    .map(c -> nextNode != null && c.completed() && c.next().isEmpty() ? Completion.elsewhere(nextNode) : c);
        }
        @Override public Future<Completion> completeRow(long seq, int attempts) {
            return rows.completeRow(seq, attempts).map(c -> nextNode != null && c.completed() ? Completion.elsewhere(nextNode) : c);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return rows.renew(seq, attempts, lease); }
        @Override public Future<Boolean> defer(long seq, int attempts, Duration delay) { return rows.defer(seq, attempts, delay); }
        @Override public Future<Boolean> restart(String messageId, String flow, String partitionKey, int attempt) { return rows.restart(messageId, flow, partitionKey, attempt); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return rows.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return rows.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return rows.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return rows.hasItems(flow); }
    }

    /** A keyed message recorded and queued under key {@code K}; answers its id. */
    String queued() {
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        rows.enqueue(in.id(), "orders", 1, "K").await();
        return in.id();
    }

    /** The worker over {@code queue}, built with the recording signals, not started; the gate open. */
    FlowWorker worker(WorkQueue queue) {
        gate.open();
        worker = new FlowBuilder(rt.runtime(), List.of(gate), List.of(BuiltInStepsTest.HEADERS), Map.of()).recording(store, Meter.system())
                .queue(queue).dlq(new InMemoryDlq()).signals(signals).worker(FlowWorkerEventsTest.def("orders", 2), Path.of(""), "n-a",
                        new WorkerPolicy(LEASE, Duration.ofMinutes(10)));
        return worker;
    }

    @Test
    void aFastPathToldTheNextRowsNodeAnnouncesItThere() throws Exception {
        queued();
        queued();
        assertEquals(1, worker(new NodeBound(rows, "n-b")).poll().await(5, TimeUnit.SECONDS), "the key's first row claimed; the second taken by the fast path");
        assertEquals(0, rows.size(), "both ran and completed");
        assertEquals(List.of("n-b orders"), announced, "once: the fast path that took the second row announced nothing, the one told n-b announced there");
    }

    /** A plain keyed complete (the chain bound 0: no fast path) told the next row's node announces it there too (B4 (i)). */
    @Test
    void aPlainCompleteToldTheNextRowsNodeAnnouncesItThere() throws Exception {
        queued();
        gate.open();
        worker = new FlowBuilder(rt.runtime(), List.of(gate), List.of(BuiltInStepsTest.HEADERS), Map.of()).recording(store, Meter.system())
                .queue(new NodeBound(rows, "n-b")).dlq(new InMemoryDlq()).signals(signals).worker(FlowWorkerEventsTest.def("orders", 2), Path.of(""),
                        "n-a", new WorkerPolicy(LEASE, Duration.ofMinutes(10), 0));
        assertEquals(1, worker.poll().await(5, TimeUnit.SECONDS), "claimed and run");
        assertEquals(0, rows.size(), "completed plainly");
        assertEquals(List.of("n-b orders"), announced, "the complete named n-b: announced there");
    }

    /** A builder never given signals: a fast path told the next row's node completes all the same, telling nobody. */
    @Test
    void aWorkerBuiltWithoutSignalsCompletesAllTheSame() throws Exception {
        queued();
        gate.open();
        worker = new FlowBuilder(rt.runtime(), List.of(gate), List.of(BuiltInStepsTest.HEADERS), Map.of()).recording(store, Meter.system())
                .queue(new NodeBound(rows, "n-b")).dlq(new InMemoryDlq()).worker(FlowWorkerEventsTest.def("orders", 2), Path.of(""), "n-a",
                        new WorkerPolicy(LEASE, Duration.ofMinutes(10)));
        assertEquals(1, worker.poll().await(5, TimeUnit.SECONDS), "claimed and run");
        assertEquals(0, rows.size(), "completed, the next node told to no signals at all");
        assertEquals(List.of(), announced, "the recording signals were not this worker's");
    }

    /** A keyed message recorded and queued under key {@code key}; answers its id. */
    String queuedUnder(String key) {
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        rows.enqueue(in.id(), "orders", 1, key).await();
        return in.id();
    }

    /** The signals' announce throws, as one whose window's timer refuses (review r1 V4); every call counted first. */
    final java.util.concurrent.atomic.AtomicInteger refusedAnnounces = new java.util.concurrent.atomic.AtomicInteger();
    final IllegalStateException refusal = new IllegalStateException("the work events' timer refused the window");
    final WorkSignals throwing = (target, flow) -> {
        refusedAnnounces.incrementAndGet();
        throw refusal;
    };
    final java.util.logging.Logger workerLog = java.util.logging.Logger.getLogger(FlowWorker.class.getName());   // held: JUL keeps loggers weakly
    final List<java.util.logging.LogRecord> warnings = new CopyOnWriteArrayList<>();
    final java.util.logging.Handler warned = new java.util.logging.Handler() {
        @Override public void publish(java.util.logging.LogRecord r) {
            if (r.getLevel() == java.util.logging.Level.WARNING) warnings.add(r);
        }
        @Override public void flush() { }
        @Override public void close() { }
    };

    /** The worker over {@code queue} under {@code maxChain}, announcing through the throwing signals; JUL captured. */
    FlowWorker throwingWorker(WorkQueue queue, int maxChain) {
        gate.open();
        workerLog.addHandler(warned);
        worker = new FlowBuilder(rt.runtime(), List.of(gate), List.of(BuiltInStepsTest.HEADERS), Map.of()).recording(store, Meter.system())
                .queue(queue).dlq(new InMemoryDlq()).signals(throwing).worker(FlowWorkerEventsTest.def("orders", 2), Path.of(""), "n-a",
                        new WorkerPolicy(LEASE, Duration.ofMinutes(10), maxChain));
        return worker;
    }

    @AfterEach
    void unhook() { workerLog.removeHandler(warned); }

    /** What every throwing-announce case asserts once the claim settled: answered, completed, settled, logged, the room kept. */
    void settledDespiteTheThrow(Future<Integer> poll, int announces) throws Exception {
        assertEquals(1, poll.await(5, TimeUnit.SECONDS), "the claim answers its item, the announce's throw not passed to it");
        assertEquals(announces, refusedAnnounces.get(), "witness: the announce was made and threw");
        assertEquals(0, rows.size(), "the row completed");
        assertEquals(0, worker.unsettled(), "settled: nothing left running");
        assertEquals(0, worker.staleSettles(), "no stale settle counted");
        org.junit.jupiter.api.Assertions.assertTrue(warnings.stream().anyMatch(r -> r.getThrown() == refusal
                        || String.valueOf(r.getMessage()).contains(refusal.getMessage())),
                "the throw logged at WARNING: " + warnings.stream().map(java.util.logging.LogRecord::getMessage).toList());
        queuedUnder("A");
        queuedUnder("B");
        assertEquals(2, worker.poll().await(5, TimeUnit.SECONDS), "the worker keeps its room: a later claim takes its full max of 2");
        assertEquals(0, rows.size(), "and runs them");
    }

    /** Review r1 V4, FlowWorker.settle: a plain keyed complete told the next row's node, the announce throwing after the delete. */
    @Test
    void aPlainCompleteWhoseAnnounceThrowsStillSettlesAndKeepsItsRoom() throws Exception {
        queued();
        settledDespiteTheThrow(throwingWorker(new NodeBound(rows, "n-b"), 0).poll(), 1);
    }

    /** Review r1 V4, FlowWorker.taken: a fast path that took nothing, told the next row's node, the announce throwing after its commit. */
    @Test
    void aFastPathWhoseAnnounceThrowsStillSettlesAndKeepsItsRoom() throws Exception {
        queued();
        settledDespiteTheThrow(throwingWorker(new NodeBound(rows, "n-b"), 5).poll(), 1);
    }

    @Test
    void aFastPathToldNoNodeOrTakingTheNextRowAnnouncesNothing() throws Exception {
        queued();
        queued();
        assertEquals(1, worker(new NodeBound(rows, null)).poll().await(5, TimeUnit.SECONDS), "the key's first row claimed; the second taken by the fast path");
        assertEquals(0, rows.size(), "both ran and completed");
        assertEquals(List.of(), announced, "nothing announced: the next row was taken, then the key had none");
    }
}
