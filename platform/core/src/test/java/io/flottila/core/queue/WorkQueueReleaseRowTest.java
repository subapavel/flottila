package io.flottila.core.queue;

import io.flottila.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b (D-121 (b) B4): {@link WorkQueue#releaseRow} — its default (a queue that answers only {@link WorkQueue#release}, observed
 * through {@link WorkQueueEnqueueLeasedDefaultTest.Plain}), {@link InMemoryWorkQueue}'s override — and the new parts of
 * {@link WorkQueue.Completion} and {@link WorkQueue.Released}.
 */
class WorkQueueReleaseRowTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(FakeClock.at(Instant.parse("2026-11-10T10:00:00Z")));

    /** {@code id} of {@code flow} enqueued, claimed and held: its seq. */
    long held(String id, String flow) {
        rows.enqueue(id, flow, 1, null).await();
        List<WorkQueue.Item> taken = rows.claim("w1", flow, 1, LEASE).await();
        assertTrue(rows.hold(taken.get(0).seq(), taken.get(0).attempts()).await(), "fixture: held");
        return taken.get(0).seq();
    }

    @Test
    void theDefaultReleasesAndCannotTellTheRow() {
        WorkQueue plain = new WorkQueueEnqueueLeasedDefaultTest.Plain(rows);
        long seq = held("m-1", "orders");
        assertEquals(Optional.of(WorkQueue.Released.UNTOLD), plain.releaseRow(seq).await(), "released: neither flow nor node told");
        assertEquals(Optional.empty(), rows.held(Optional.empty(), 10).await().items().stream().findFirst(), "the row is no longer held");
        assertEquals(Optional.empty(), plain.releaseRow(seq).await(), "a row not held: nothing released");
    }

    /** {@code completeRow}'s default (the in-memory queue's too): completed with no next node, or stale. */
    @Test
    void theDefaultCompleteRowNamesNoNode() {
        WorkQueue plain = new WorkQueueEnqueueLeasedDefaultTest.Plain(rows);
        rows.enqueue("m-1", "orders", 1, "K").await();
        rows.enqueue("m-2", "orders", 1, "K").await();
        WorkQueue.Item it = rows.claim("w1", "orders", 1, LEASE).await().get(0);
        assertEquals(WorkQueue.Completion.STALE, plain.completeRow(it.seq(), it.attempts() + 1).await(), "not the holder's token: stale");
        assertEquals(new WorkQueue.Completion(true, Optional.empty()), plain.completeRow(it.seq(), it.attempts()).await(),
                "completed; no node named though the key has a next row");
        assertEquals(1, rows.size(), "m-1 gone, m-2 left");
        assertEquals(WorkQueue.Completion.STALE, rows.completeRow(it.seq(), it.attempts()).await(), "the in-memory queue: completed once");
    }

    @Test
    void theInMemoryQueueTellsTheReleasedRowsFlow() {
        long seq = held("m-1", "invoices");
        assertEquals(Optional.of(new WorkQueue.Released(Optional.of("invoices"), Optional.empty())), rows.releaseRow(seq).await(),
                "its flow; no body node: it holds no bodies");
        assertEquals(Optional.empty(), rows.releaseRow(seq).await(), "released once");
        assertEquals(Optional.empty(), rows.releaseRow(999).await(), "an unknown seq");
        long again = held("m-2", "invoices");
        assertTrue(rows.release(again).await(), "release answers the same as a boolean");
        assertFalse(rows.release(again).await());
    }

    @Test
    void aCompletionNamesANextNodeOnlyWhenItTookNothing() {
        WorkQueue.Item next = new WorkQueue.Item(2, "m-2", "orders", 1, "K", 1);
        assertEquals(new WorkQueue.Completion(true, Optional.empty(), Optional.of("n-b")), WorkQueue.Completion.elsewhere("n-b"));
        assertEquals(Optional.empty(), WorkQueue.Completion.done(Optional.of(next)).nextNode(), "the two-part forms name no node");
        assertEquals(Optional.empty(), WorkQueue.Completion.STALE.nextNode());
        assertThrows(IllegalArgumentException.class, () -> new WorkQueue.Completion(false, Optional.empty(), Optional.of("n-b")), "not completed");
        assertThrows(IllegalArgumentException.class, () -> new WorkQueue.Completion(true, Optional.of(next), Optional.of("n-b")), "a row taken");
        assertThrows(NullPointerException.class, () -> new WorkQueue.Completion(true, Optional.empty(), null));
        assertThrows(NullPointerException.class, () -> new WorkQueue.Released(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new WorkQueue.Released(Optional.empty(), null));
    }
}
