package dev.rail8.core.batch;

import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-195 (c): the batches a drain produces reach the sink oldest first, in the order the take produced them, so the
 * concatenation of the batches in hand-off order is arrival order. The writer's global order is the queue (FIFO) and
 * then the waiters (FIFO), and a waiter exists only while the queue is full, so every queued item is older than every
 * waiter. Documented non-guarantee, not asserted: a sink that completes inline re-enters the drain, so a younger batch
 * can reach the sink before the outer loop's next one.
 */
class BatchingWriterDispatchOrderTest {

    static final Duration WINDOW = Duration.ofMillis(50);

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final FakeTimer timer = new FakeTimer(clock);
    final FakeSink sink = new FakeSink();

    /**
     * Two full batches wait behind two commits that hold both slots of a {@code commitConcurrency 2} writer; a sink that
     * records the lists it is handed and completes only on demand shows the order they are handed on in. The writer never
     * takes two batches under one lock at this concurrency (a free slot is filled by the enqueue that fills the batch), so
     * what is pinned here is the hand-off order of the batches a loaded backlog produces.
     */
    @Test
    void oneDrainHandsItsBatchesToTheSinkOldestFirst() {
        sink.manual = true;
        int n = 3;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(n, WINDOW, 2, 100));

        Future<Void> f1 = w.save("filler1");                // takes the first slot at once
        Future<Void> f2 = w.save("filler2");
        w.flush();                                          // the window elapsed: filler2 takes the second slot
        assertEquals(List.of(List.of("filler1"), List.of("filler2")), sink.batches, "fixture: both commit slots are busy");

        List<String> items = new ArrayList<>();
        List<Future<Void>> saved = new ArrayList<>();
        for (int i = 0; i < 2 * n; i++) {
            items.add("i" + i);
            saved.add(w.save("i" + i));
        }
        assertEquals(2 * n, w.backlog(), "fixture: two full batches are queued while both slots are busy");
        assertEquals(2, sink.batches.size(), "fixture: nothing more was handed to the sink yet");

        sink.gate().complete();                             // filler1's slot frees: the oldest batch goes
        sink.gate().complete();                             // filler2's slot frees: the next one goes
        assertTrue(f1.succeeded() && f2.succeeded(), "fixture: both fillers committed");

        assertEquals(List.of(items.subList(0, n), items.subList(n, 2 * n)), sink.batches.subList(2, sink.batches.size()),
                "the batches reach the sink oldest first: " + sink.batches);
        while (sink.open() > 0) sink.gate().complete();
        for (int i = 0; i < saved.size(); i++) assertTrue(saved.get(i).succeeded(), items.get(i) + " committed: " + saved.get(i).cause());
        assertEquals(items, sink.batches.subList(2, sink.batches.size()).stream().flatMap(List::stream).toList(),
                "the concatenation of the batches in hand-off order is arrival order");
    }
}
