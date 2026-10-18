package io.flottila.core.batch;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** flush(): commit what is queued now without waiting for the window — the recorder's stores use it to drain at close and in tests. */
class BatchingWriterFlushTest {

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final FakeTimer timer = new FakeTimer(clock);
    final List<List<Integer>> batches = new ArrayList<>();
    final List<Promise<Void>> commits = new ArrayList<>();
    /** One commit at a time, each completed by the test: the first item of an idle writer goes at once, the next ones queue behind it. */
    final BatchingWriter<Integer> writer = new BatchingWriter<>(b -> { batches.add(List.copyOf(b)); Promise<Void> p = Promise.promise(); commits.add(p); return p.future(); },
            timer, new BatchingWriter.Options(100, Duration.ofMillis(10), 1, 1000));

    @Test
    void flushCommitsTheQueuedItemsWithoutTheWindowAndCompletesWhenTheyAreCommitted() {
        writer.save(1);
        Future<Void> two = writer.save(2);
        Future<Void> three = writer.save(3);
        assertEquals(List.of(List.of(1)), batches, "1 went at once; 2 and 3 wait behind it for the window or the batch size");
        assertEquals(1, timer.pending(), "the window is armed");
        Future<Void> flushed = writer.flush();
        assertFalse(flushed.isComplete(), "the slot is busy: the queued items go when it frees, not before");
        commits.get(0).complete();
        assertEquals(List.of(List.of(1), List.of(2, 3)), batches, "the slot freed: the queued items go as one batch, no window wait");
        assertFalse(flushed.isComplete(), "not yet committed");
        commits.get(1).complete();
        assertTrue(two.succeeded() && three.succeeded() && flushed.succeeded());
        assertTrue(writer.flush().succeeded(), "nothing queued: completes at once");
        assertEquals(2, batches.size());
        clock.advance(Duration.ofMillis(20));
        assertEquals(2, batches.size(), "no window timer is left to fire");
    }

    @Test
    void flushCommitsAQueuedItemAtOnceWhenASlotIsFreeBesideARunningCommit() {
        BatchingWriter<Integer> two = new BatchingWriter<>(b -> { batches.add(List.copyOf(b)); Promise<Void> p = Promise.promise(); commits.add(p); return p.future(); },
                timer, new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        two.save(1);
        Future<Void> second = two.save(2);
        assertEquals(List.of(List.of(1)), batches, "one commit running: the next item waits for the window although a second slot is free");
        two.flush();
        assertEquals(List.of(List.of(1), List.of(2)), batches, "flush uses the free slot now");
        commits.get(1).complete();
        assertTrue(second.succeeded());
    }

    @Test
    void flushWithAFreeSlotCommitsAtOnceAndReportsAFailedCommit() {
        BatchingWriter<Integer> wide = new BatchingWriter<>(b -> { batches.add(List.copyOf(b)); return Future.failedFuture(new IllegalStateException("db down")); },
                timer, new BatchingWriter.Options(100, Duration.ofMillis(10), 4, 1000));
        Future<Void> one = wide.save(1);
        assertTrue(one.failed(), "went at once and failed at once");
        Future<Void> flushed = writer.flush();
        assertTrue(flushed.succeeded(), "an idle writer has nothing to flush");
        writer.save(1);
        writer.save(2);
        Future<Void> f = writer.flush();
        commits.get(0).complete();
        commits.get(1).fail(new IllegalStateException("db down"));
        assertTrue(f.failed(), "a flush reports the failure of what it drained");
    }
}
