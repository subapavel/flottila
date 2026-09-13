package io.flottila.runtime;

import io.flottila.core.Body;
import io.flottila.core.Envelope;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class BatchingWriterTest {

    /** Records batches; in manual mode each saveAll waits for a gate the test completes. */
    static final class FakeStore implements MessageStore {
        final List<List<Envelope>> batches = new CopyOnWriteArrayList<>();
        final BlockingQueue<CompletableFuture<Void>> gates = new LinkedBlockingQueue<>();
        volatile boolean manual = false;

        public Uni<Void> save(Envelope e) { return saveAll(List.of(e)); }
        public Uni<Void> saveAll(List<Envelope> batch) {
            batches.add(batch);
            if (!manual) return Uni.createFrom().voidItem();
            CompletableFuture<Void> f = new CompletableFuture<>();
            gates.add(f);
            return Uni.createFrom().completionStage(f);
        }
        public Uni<Optional<Envelope>> load(String id, int step) { return Uni.createFrom().item(Optional.empty()); }
        CompletableFuture<Void> gate() throws InterruptedException {
            CompletableFuture<Void> g = gates.poll(2, TimeUnit.SECONDS);
            assertNotNull(g, "expected a saveAll in flight");
            return g;
        }
    }

    private static Envelope env() { return Envelope.ingress("test", Map.of(), Body.of("<x/>")); }

    @Test void idleFlushIsImmediate() {
        FakeStore store = new FakeStore();
        BatchingWriter w = new BatchingWriter(store, 100, 5000, 4);
        w.save(env()).await().atMost(Duration.ofSeconds(1));       // no 5 s window wait
        assertEquals(1, store.batches.size());
        assertEquals(1, store.batches.get(0).size());
    }

    @Test void fullBatchFlushesWhileACommitIsInFlight() throws Exception {
        FakeStore store = new FakeStore(); store.manual = true;
        BatchingWriter w = new BatchingWriter(store, 3, 60_000, 4);
        CompletableFuture<Void> first = w.save(env()).subscribeAsCompletionStage();
        var gateA = store.gate();
        w.save(env()); w.save(env());
        CompletableFuture<Void> lastOfBatch = w.save(env()).subscribeAsCompletionStage();  // 3rd queued -> full batch
        assertEquals(2, store.batches.size(), "full batch must flush despite busy slot");
        assertEquals(3, store.batches.get(1).size());
        assertFalse(first.isDone()); assertFalse(lastOfBatch.isDone());
        gateA.complete(null);
        first.get(1, TimeUnit.SECONDS);
    }

    @Test void timerFlushesPartialBatch() throws Exception {
        FakeStore store = new FakeStore(); store.manual = true;
        BatchingWriter w = new BatchingWriter(store, 100, 50, 4);
        w.save(env());                                             // immediate: batch A in flight
        store.gate();
        w.save(env()); w.save(env());                              // partial, below batchSize
        assertEquals(1, store.batches.size(), "partial batch must wait for the timer");
        Thread.sleep(250);
        assertEquals(2, store.batches.size(), "timer must flush the partial batch");
        assertEquals(2, store.batches.get(1).size());
    }

    @Test void concurrentCommitsCompletePerBatch() throws Exception {
        FakeStore store = new FakeStore(); store.manual = true;
        BatchingWriter w = new BatchingWriter(store, 1, 0, 2);
        CompletableFuture<Void> a = w.save(env()).subscribeAsCompletionStage();
        var gateA = store.gate();
        CompletableFuture<Void> b = w.save(env()).subscribeAsCompletionStage();
        var gateB = store.gate();
        CompletableFuture<Void> c = w.save(env()).subscribeAsCompletionStage();  // both slots busy -> queued
        assertEquals(2, store.batches.size());
        gateB.complete(null);
        b.get(1, TimeUnit.SECONDS);
        assertFalse(a.isDone(), "completing batch B must not complete batch A");
        store.gate().complete(null);                               // c flushed when B's slot freed
        gateA.complete(null);
        a.get(1, TimeUnit.SECONDS);
        c.get(1, TimeUnit.SECONDS);
        assertEquals(3, store.batches.size());
    }

    @Test void overflowFailsFastWithOverloaded() throws Exception {
        FakeStore store = new FakeStore(); store.manual = true;
        BatchingWriter w = new BatchingWriter(store, 1, 0, 1);      // capacity = 64
        w.save(env()); store.gate();                                // slot busy
        for (int i = 0; i < 64; i++) w.save(env());                 // fill the queue
        long t0 = System.nanoTime();
        var failed = w.save(env()).subscribeAsCompletionStage();
        assertTrue(failed.isCompletedExceptionally(), "over-capacity save must fail immediately");
        assertTrue(System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(100));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> failed.get(1, TimeUnit.SECONDS));
        assertInstanceOf(BatchingWriter.OverloadedException.class, ex.getCause());
    }

    @Test void commitFailureFailsOnlyItsOwnBatch() throws Exception {
        FakeStore store = new FakeStore(); store.manual = true;
        BatchingWriter w = new BatchingWriter(store, 1, 0, 2);
        CompletableFuture<Void> a = w.save(env()).subscribeAsCompletionStage();
        var gateA = store.gate();
        CompletableFuture<Void> b = w.save(env()).subscribeAsCompletionStage();
        var gateB = store.gate();
        gateA.completeExceptionally(new RuntimeException("db down"));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> a.get(1, TimeUnit.SECONDS));
        assertEquals("db down", ex.getCause().getMessage());
        assertFalse(b.isDone(), "failure of batch A must not touch batch B");
        gateB.complete(null);
        b.get(1, TimeUnit.SECONDS);
    }
}
