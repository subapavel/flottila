package io.flottila.core.batch;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six cases of proto-m1's BatchingWriterTest, same intent, on Vert.x Future with the fake timer
 * instead of Thread.sleep; plus the options validation the prototype did not have.
 */
class BatchingWriterTest {

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final FakeTimer timer = new FakeTimer(clock);
    final FakeSink sink = new FakeSink();

    private BatchingWriter<String> writer(int batchSize, long windowMs, int concurrency, int maxBacklog) {
        return new BatchingWriter<>(sink, timer, new BatchingWriter.Options(batchSize, Duration.ofMillis(windowMs), concurrency, maxBacklog));
    }

    @Test
    void idleFlushIsImmediate() {
        BatchingWriter<String> w = writer(100, 5_000, 4, 6_400);
        assertTrue(w.save("a").succeeded(), "completes inline: the sink answered and the clock never moved");
        assertEquals(1, sink.batches.size());
        assertEquals(1, sink.batches.get(0).size());
        assertEquals(0, timer.pending(), "nothing left to wait for");
    }

    @Test
    void fullBatchFlushesWhileACommitIsInFlight() {
        sink.manual = true;
        BatchingWriter<String> w = writer(3, 60_000, 4, 192);
        Future<Void> first = w.save("a");
        var gateA = sink.gate();
        w.save("b"); w.save("c");
        Future<Void> lastOfBatch = w.save("d");       // third queued → full batch
        assertEquals(2, sink.batches.size(), "a full batch must flush despite the busy slot");
        assertEquals(3, sink.batches.get(1).size());
        assertFalse(first.isComplete());
        assertFalse(lastOfBatch.isComplete());
        assertEquals(2, w.inFlight(), "both batches are open");
        gateA.complete();
        assertTrue(first.succeeded());
        assertEquals(1, w.inFlight(), "the second batch is still open");
    }

    @Test
    void theWindowTimerFlushesAPartialBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(100, 50, 4, 6_400);
        w.save("a");                                  // immediate: batch A in flight
        sink.gate();
        w.save("b"); w.save("c");                     // partial, below batchSize
        assertEquals(1, sink.batches.size(), "a partial batch waits for the window");
        assertEquals(2, w.backlog());
        clock.advance(Duration.ofMillis(49));
        assertEquals(1, sink.batches.size(), "not yet");
        clock.advance(Duration.ofMillis(1));
        assertEquals(2, sink.batches.size(), "the window flushes the partial batch");
        assertEquals(2, sink.batches.get(1).size());
        assertEquals(0, w.backlog());
    }

    @Test
    void concurrentCommitsCompletePerBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 2, 64);
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        var gateB = sink.gate();
        Future<Void> c = w.save("c");                 // both slots busy → queued
        assertEquals(2, sink.batches.size());
        assertEquals(2, w.inFlight());
        gateB.complete();
        assertTrue(b.succeeded());
        assertFalse(a.isComplete(), "completing batch B must not complete batch A");
        assertEquals(3, sink.batches.size(), "c flushed when B's slot freed");
        sink.gate().complete();
        gateA.complete();
        assertTrue(a.succeeded());
        assertTrue(c.succeeded());
    }

    @Test
    void overflowFailsFastWithOverloaded() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 1, 64);
        w.save("busy"); sink.gate();                  // the only slot is busy
        for (int i = 0; i < 64; i++) w.save("q" + i); // fill the backlog
        assertEquals(64, w.backlog());
        Future<Void> failed = w.save("one too many");
        assertTrue(failed.failed(), "an over-capacity save fails immediately, it never waits");
        OverloadedException e = assertInstanceOf(OverloadedException.class, failed.cause());
        assertEquals(64L, e.capacity());
        assertTrue(e.getMessage().contains("64"));
        assertEquals(1, sink.batches.size(), "the rejected item never reaches the sink");
    }

    @Test
    void commitFailureFailsOnlyItsOwnBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 2, 64);
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        var gateB = sink.gate();
        gateA.fail(new RuntimeException("db down"));
        assertTrue(a.failed());
        assertEquals("db down", a.cause().getMessage());
        assertFalse(b.isComplete(), "the failure of batch A must not touch batch B");
        gateB.complete();
        assertTrue(b.succeeded());
        assertEquals(0, w.inFlight());
    }

    @Test
    void aFailedBatchFreesItsSlotForTheQueue() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 1, 64);
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        assertEquals(1, sink.batches.size());
        gateA.fail(new RuntimeException("db down"));
        assertTrue(a.failed());
        assertEquals(2, sink.batches.size(), "b is committed after a's slot is freed");
        sink.gate().complete();
        assertTrue(b.succeeded());
    }

    @Test
    void optionsAreFiniteAndPositive() {
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(0, Duration.ZERO, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ofMillis(-1), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(10, Duration.ZERO, 1, 9),
                "a backlog smaller than one batch can never fill a batch");
        BatchingWriter.Options defaults = BatchingWriter.Options.defaults();
        assertEquals(100, defaults.batchSize());
        assertEquals(Duration.ofMillis(10), defaults.window());
        assertEquals(4, defaults.commitConcurrency());
        assertEquals(6_400, defaults.maxBacklog(), "the prototype's batchSize × 64");
    }

    @Test
    void savesAreRejectedAfterClose() {
        BatchingWriter<String> w = writer(10, 10, 1, 100);
        assertTrue(w.save("a").succeeded());
        w.close();
        Future<Void> late = w.save("late");
        assertTrue(late.failed());
        assertInstanceOf(IllegalStateException.class, late.cause());
        assertEquals(1, sink.batches.size());
    }

    @Test
    void closeStillFlushesWhatWasQueued() {
        sink.manual = true;
        BatchingWriter<String> w = writer(10, 50, 2, 100);
        w.save("a"); var gateA = sink.gate();
        Future<Void> b = w.save("b");
        w.close();
        assertEquals(1, w.backlog(), "b waits for the window while a is in flight");
        clock.advance(Duration.ofMillis(50));          // the window still fires after close: b uses the second slot
        assertEquals(2, sink.batches.size(), "b was flushed by the window, not by a's completion");
        assertEquals(0, w.backlog());
        sink.gate().complete();
        assertTrue(b.succeeded(), "nothing accepted before close is lost");
        gateA.complete();
    }

    @Test
    void theSlotIsReleasedBeforeCallersAreCompleted() {
        sink.manual = true;
        BatchingWriter<String> w = writer(2, 0, 1, 10);
        Future<Void> a = w.save("a");                  // immediate: batch 1 = [a]
        var gateA = sink.gate();
        a.onSuccess(v -> {
            // a completion listener saving again: with the slot already free, its save is handed to the sink at once
            w.save("c");
            assertEquals(2, sink.batches.size(), "c committed inline from a's completion listener");
        });
        gateA.complete();
        assertTrue(a.succeeded());
        assertEquals(2, sink.batches.size());
        assertEquals(1, w.inFlight(), "c holds the only slot");
        sink.gate().complete();
        assertEquals(0, w.inFlight());
    }

    @Test
    void aThrowingCompletionListenerDoesNotSkipTheRestOfTheBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(2, 0, 1, 10);
        Future<Void> first = w.save("first");          // immediate: batch 1 = [first]
        var gate1 = sink.gate();
        Future<Void> a = w.save("a");
        Future<Void> b = w.save("b");                  // batch 2 = [a, b], queued behind the busy slot
        a.onSuccess(v -> { throw new IllegalStateException("listener bug"); });
        gate1.complete();
        assertTrue(first.succeeded());
        assertEquals(2, sink.batches.size());
        sink.gate().complete();                        // a's listener throws (contained by Vert.x); b must still complete
        assertTrue(a.succeeded());
        assertTrue(b.succeeded(), "the throwing listener of a did not skip b");
        assertEquals(0, w.inFlight());
        w.save("c");
        assertEquals(3, sink.batches.size(), "the writer still works after the listener failure");
        sink.gate().complete();
    }
}
