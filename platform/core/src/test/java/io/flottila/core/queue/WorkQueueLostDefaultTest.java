package io.flottila.core.queue;

import io.flottila.core.record.Page;
import io.flottila.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FIX-141 (D-126): the defaults of {@link WorkQueue#lost} and {@link WorkQueue#discard} — a queue that holds no bodies never loses one,
 * observed through {@link InMemoryWorkQueue}, which keeps both — and the new parts of {@link WorkQueue.Held} and
 * {@link WorkQueue.Discarded}.
 */
class WorkQueueLostDefaultTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(FakeClock.at(Instant.parse("2026-11-11T10:00:00Z")));

    @Test
    void theDefaultListsNoLostRowAndChecksTheLimitAsHeldDoes() {
        rows.enqueue("m-1", "orders", 1, "K").await();
        assertEquals(new Page<WorkQueue.Lost>(List.of(), Optional.empty()), rows.lost(Optional.empty(), 10).await(), "nothing lost, a last page");
        assertThrows(IllegalArgumentException.class, () -> rows.lost(Optional.empty(), 0), "a limit below 1, at the call");
        assertThrows(IllegalArgumentException.class, () -> rows.held(Optional.empty(), 0), "witness: held refuses the same limit");
        assertThrows(IllegalArgumentException.class, () -> rows.lost(Optional.empty(), Page.MAX_LIMIT + 1), "a limit above the page maximum");
    }

    @Test
    void theDefaultDiscardsNothingAndTheRowStaysClaimable() {
        rows.enqueue("m-1", "orders", 1, null).await();
        long seq = rows.claim("w1", "orders", 1, LEASE).await().get(0).seq();
        assertEquals(Optional.empty(), rows.discard(seq, "ops-anna", "10.0.0.7").await(), "no lost row: nothing discarded");
        assertEquals(1, rows.size(), "the row is still there");
        assertEquals(Optional.empty(), rows.discard(seq + 1000, "ops-anna", "10.0.0.7").await(), "an unknown seq: nothing either");
    }

    /** The in-memory queue counts no blocks: a held keyed head with a row of its key behind it lists {@code blocks} 0. */
    @Test
    void aHeldRowOfAQueueThatDoesNotCountBlocksBlocksZero() {
        rows.enqueue("m-1", "orders", 1, "K").await();
        rows.enqueue("m-2", "orders", 1, "K").await();
        WorkQueue.Item head = rows.claim("w1", "orders", 1, LEASE).await().get(0);
        rows.hold(head.seq(), head.attempts()).await();
        List<WorkQueue.Held> held = rows.held(Optional.empty(), 10).await().items();
        assertEquals(List.of(head), held.stream().map(WorkQueue.Held::item).toList(), "fixture: the head held");
        assertEquals(0, held.getFirst().blocks());
        assertEquals(new WorkQueue.Held(head, held.getFirst().heldAt(), 0), new WorkQueue.Held(head, held.getFirst().heldAt()), "the two-part form is blocks 0");
    }

    /** A lost row is a value of its parts; its item compares as a hand-out does, without its doubts. */
    @Test
    void aLostRowIsEqualByItsHandOutItsInstantAndWhatItBlocks() {
        Instant at = Instant.parse("2026-11-04T10:00:00Z");
        WorkQueue.Item doubted = new WorkQueue.Item(3, "m-1", "orders", 1, "K", 2, 1);
        WorkQueue.Item plain = new WorkQueue.Item(3, "m-1", "orders", 1, "K", 2);
        assertEquals(new WorkQueue.Lost(plain, at, 2), new WorkQueue.Lost(doubted, at, 2), "the same hand-out: doubts not compared");
        assertNotEquals(new WorkQueue.Lost(plain, at, 2), new WorkQueue.Lost(plain, at, 1), "another count behind it");
    }

    @Test
    void aDiscardedNamesItsFlowAndNodeNeverNull() {
        WorkQueue.Discarded d = new WorkQueue.Discarded(7, "orders", Optional.of("n-b"));
        assertEquals(List.of(7L, "orders", Optional.of("n-b")), List.of(d.auditId(), d.flow(), d.nextNode()));
        assertThrows(NullPointerException.class, () -> new WorkQueue.Discarded(7, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new WorkQueue.Discarded(7, "orders", null));
    }
}
