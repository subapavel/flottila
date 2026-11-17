package dev.rail8.sim;

import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.queue.WorkQueue.Item;
import dev.rail8.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The REORDERING_FAST_PATH variant's queue is the in-memory queue but for its defect: the fast path takes the newest row. */
class ReorderingFastPathTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    /** Three rows of key k, the first claimed and completed through the fast path; the message ids of what follows. */
    private static List<String> script(WorkQueue q) {
        for (String id : List.of("m-1", "m-2", "m-3")) q.enqueue(id, "alo", 1, "k").await();
        Item first = q.claim("a", "alo", 4, LEASE).await().getFirst();
        Item next = q.completeAndTakeNext(first.seq(), first.attempts(), "a", LEASE).await().next().orElseThrow();
        List<Item> meanwhile = q.claim("b", "alo", 4, LEASE).await();
        Item last = q.completeAndTakeNext(next.seq(), next.attempts(), "a", LEASE).await().next().orElseThrow();
        return List.of(first.messageId(), next.messageId(), Integer.toString(meanwhile.size()), last.messageId());
    }

    @Test
    void theFastPathTakesTheNewestDueRowOfTheKeyAndNothingElseMoves() {
        FakeClock clock = FakeClock.at(Instant.parse("2026-11-03T10:00:00Z"));
        assertEquals(List.of("m-1", "m-2", "0", "m-3"), script(new InMemoryWorkQueue(clock)), "the in-memory queue's order");
        assertEquals(List.of("m-1", "m-3", "0", "m-2"), script(new ReorderingFastPath.NewestFirstQueue(clock, new InMemoryDlq())), "the variant's order");
    }
}
