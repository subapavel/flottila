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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-7 C1 (D-122) in core: a worker settles an item to complete that has no partition key by {@link WorkQueue#completeUnkeyed},
 * a keyed one by {@link WorkQueue#completeRow} (no fast path: the chain bound 0, or the worker stopped) or by the fast path
 * {@link WorkQueue#completeAndTakeNext} — unchanged; never a keyed item by completeUnkeyed, never an unkeyed one by completeRow. What
 * the unkeyed complete answers is handled as completeRow's answer is: STALE is counted in {@code staleSettles}, a failure fails the
 * round and leaves the item to its lease (its lease-end wake claims again).
 *
 * <p>The queue is an in-memory one behind {@link Entries}, a decorator at the store boundary that logs which settle entry point
 * each call used ({@code unkeyed 1@1}, {@code row 2@1}, {@code take 3@1}, {@code complete 4@1}) and can answer the next
 * completeUnkeyed itself (STALE, or a failure) without reaching the rows. Delegated calls go to the in-memory queue's own
 * methods, so a default that routes one entry point through another logs only the entry the worker called.
 *
 * <p>The API assumed (CORE-7 packet): {@code Future<Completion> WorkQueue.completeUnkeyed(long seq, int attempts)}, a default
 * answering as {@code completeRow}.
 */
@Timeout(30)
class FlowWorkerCompleteUnkeyedTest {

    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final RunGate gate = new RunGate();
    final Entries queue = new Entries(rows);
    FlowWorker worker;

    @AfterEach
    void tearDown() {
        if (worker != null) worker.stop();
        gate.open();
    }

    /** Logs the settle entry point of every call; the next completeUnkeyed answered by {@link #answerNextUnkeyed} if armed. */
    static final class Entries implements WorkQueue {
        final InMemoryWorkQueue rows;
        final List<String> entries = new CopyOnWriteArrayList<>();
        final AtomicInteger claims = new AtomicInteger();
        final AtomicReference<Future<Completion>> nextUnkeyed = new AtomicReference<>();

        Entries(InMemoryWorkQueue rows) { this.rows = rows; }

        void answerNextUnkeyed(Future<Completion> answer) { nextUnkeyed.set(answer); }

        List<String> sorted() { return entries.stream().sorted().toList(); }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return rows.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            claims.incrementAndGet();
            return rows.claim(workerId, flow, max, lease);
        }
        @Override public Future<Boolean> complete(long seq, int attempts) {
            entries.add("complete " + seq + "@" + attempts);
            return rows.complete(seq, attempts);
        }
        @Override public Future<Completion> completeRow(long seq, int attempts) {
            entries.add("row " + seq + "@" + attempts);
            return rows.completeRow(seq, attempts);
        }
        @Override public Future<Completion> completeUnkeyed(long seq, int attempts) {
            entries.add("unkeyed " + seq + "@" + attempts);
            Future<Completion> armed = nextUnkeyed.getAndSet(null);
            return armed != null ? armed : rows.completeUnkeyed(seq, attempts);
        }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            entries.add("take " + seq + "@" + attempts);
            return rows.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return rows.renew(seq, attempts, lease); }
        @Override public Future<Boolean> defer(long seq, int attempts, Duration delay) { return rows.defer(seq, attempts, delay); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return rows.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return rows.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return rows.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return rows.hasItems(flow); }
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** A message recorded and queued under {@code key} (null: none); items are numbered 1, 2, … in enqueue order. */
    String queued(String key) {
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        rows.enqueue(in.id(), "orders", 1, key).await();
        return in.id();
    }

    /** The worker over {@link #queue}, max in flight 2, chain bound {@code maxChain}, not started; the gate open. */
    FlowWorker worker(int maxChain) {
        gate.open();
        worker = new FlowBuilder(rt.runtime(), List.of(gate), List.of(BuiltInStepsTest.HEADERS), Map.of()).recording(store, Meter.system())
                .queue(queue).dlq(new InMemoryDlq()).worker(FlowWorkerEventsTest.def("orders", 2), Path.of(""), "n-a",
                        new WorkerPolicy(LEASE, Duration.ofMinutes(10), maxChain));
        return worker;
    }

    /** C1: one round, both items done; the unkeyed one by completeUnkeyed, the keyed one (no fast path: chain bound 0) by completeRow. */
    @Test
    void anUnkeyedItemSettlesByCompleteUnkeyedAKeyedOneByCompleteRow() throws Exception {
        queued(null);   // 1
        queued("K");    // 2
        assertEquals(2, worker(0).poll().await(5, TimeUnit.SECONDS), "both claimed and run");
        assertEquals(List.of("row 2@1", "unkeyed 1@1"), queue.sorted(),
                "the unkeyed item by completeUnkeyed, the keyed one by completeRow; neither by the other's entry point");
        assertEquals(0, rows.size(), "both completed");
        assertEquals(0, worker.staleSettles(), "nothing stale");
    }

    /** C1 "the fast path keeps its statements": keyed items go by completeAndTakeNext as before, the unkeyed neighbour by completeUnkeyed. */
    @Test
    void aKeyedItemOnTheFastPathStillCompletesAndTakesNextNeverByCompleteUnkeyed() throws Exception {
        queued("K");    // 1
        queued("K");    // 2, behind 1: taken by 1's fast path
        queued(null);   // 3
        assertEquals(2, worker(5).poll().await(5, TimeUnit.SECONDS), "1 and 3 claimed (2 waits behind 1)");
        until(() -> rows.size() == 0, () -> "2 taken by the fast path and run: " + queue.entries);
        until(() -> worker.unsettled() == 0, () -> "every chain settled: " + queue.entries);
        assertEquals(List.of("take 1@1", "take 2@1", "unkeyed 3@1"), queue.sorted(),
                "the keyed chain by the fast path only (the second answers nothing taken), the unkeyed item by completeUnkeyed");
    }

    /** C1 "stale count unchanged": completeUnkeyed answering STALE is one stale settle, nothing else asked. */
    @Test
    void aStaleCompleteUnkeyedIsCountedAsAStaleSettle() throws Exception {
        queued(null);
        queue.answerNextUnkeyed(Future.succeededFuture(WorkQueue.Completion.STALE));
        assertEquals(1, worker(0).poll().await(5, TimeUnit.SECONDS), "claimed and run");
        assertEquals(List.of("unkeyed 1@1"), queue.sorted(), "settled once, by completeUnkeyed, never tried again");
        assertEquals(1, worker.staleSettles(), "the stale answer counted");
        assertEquals(1, rows.size(), "the row as the stale answer left it: not completed by any other path");
    }

    /** C1 "leftToLease on failure unchanged": a failed completeUnkeyed fails the round and the item's lease-end wake claims again. */
    @Test
    void aFailedCompleteUnkeyedFailsTheRoundAndLeavesTheItemToItsLease() throws Exception {
        queued(null);
        IllegalStateException cause = new IllegalStateException("the batch statement failed");
        queue.answerNextUnkeyed(Future.failedFuture(cause));
        Future<Integer> round = worker(0).poll();
        until(round::isComplete, () -> "the round ends");
        assertTrue(round.failed(), "a completion that fails fails the round");
        assertSame(cause, round.cause());
        assertEquals(List.of("unkeyed 1@1"), queue.sorted(), "tried once, by completeUnkeyed: no retry, no other entry point");
        assertEquals(1, rows.size(), "the row stays");
        until(() -> worker.unsettled() == 0, () -> "the item is no longer running: " + worker.unsettled());
        int before = queue.claims.get();
        rt.clock().advance(LEASE);
        until(() -> queue.claims.get() > before, () -> "the item's lease-end wake claims again (left to its lease): claims " + queue.claims.get());
    }
}
