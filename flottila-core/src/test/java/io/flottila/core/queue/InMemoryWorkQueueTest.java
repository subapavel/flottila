package io.flottila.core.queue;

import io.flottila.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three proto-m1 WorkQueueTest cases in intent (lease expiry on the fake clock) plus the same-key fast path (ARCHITECTURE §7). */
class InMemoryWorkQueueTest {

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    static final Duration LEASE = Duration.ofSeconds(30);

    private void enqueue(String id, String key) { queue.enqueue(id, "t", 1, key).await(); }
    private List<WorkQueue.Item> claim(String worker, int max, Duration lease) { return queue.claim(worker, "t", max, lease).await(); }
    private static Set<String> ids(List<WorkQueue.Item> items) { return items.stream().map(WorkQueue.Item::messageId).collect(Collectors.toSet()); }

    @Test
    void partitionOrderingAcrossTwoWorkers() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("b1", "B"); enqueue("b2", "B"); enqueue("n1", null);
        List<WorkQueue.Item> w1 = claim("w1", 10, LEASE);
        assertEquals(Set.of("a1", "b1", "n1"), ids(w1), "only the oldest row per key plus the unordered null-key row");
        assertTrue(claim("w2", 10, LEASE).isEmpty(), "keys A and B are leased");
        long a1 = w1.stream().filter(i -> i.messageId().equals("a1")).findFirst().orElseThrow().seq();
        queue.complete(a1).await();
        assertEquals(List.of("a2"), claim("w2", 10, LEASE).stream().map(WorkQueue.Item::messageId).toList(), "completing a1 releases key A, in order");
        assertTrue(claim("w1", 10, LEASE).isEmpty(), "b2 still behind b1");
    }

    @Test
    void leaseExpiryRedelivers() {
        enqueue("x1", "K");
        List<WorkQueue.Item> first = claim("w1", 1, Duration.ofSeconds(1));
        assertEquals(1, first.size());
        assertTrue(claim("w2", 1, Duration.ofSeconds(1)).isEmpty(), "lease still active");
        clock.advance(Duration.ofMillis(999));
        assertTrue(claim("w2", 1, Duration.ofSeconds(1)).isEmpty(), "still active at 999 ms");
        clock.advance(Duration.ofMillis(2));
        List<WorkQueue.Item> second = claim("w2", 1, LEASE);
        assertEquals("x1", second.get(0).messageId(), "an expired lease re-delivers the same row");
        assertEquals(first.get(0).seq(), second.get(0).seq());
    }

    @Test
    void completeRemovesTheRow() {
        enqueue("c1", null);
        List<WorkQueue.Item> got = claim("w1", 1, LEASE);
        queue.complete(got.get(0).seq()).await();
        assertEquals(0, queue.size());
        assertTrue(claim("w1", 1, LEASE).isEmpty());
    }

    @Test
    void sameKeyFastPathHandsTheNextRowOfTheKeyToTheFinishingWorker() {
        enqueue("a1", "A"); enqueue("n0", null); enqueue("a2", "A"); enqueue("a3", "A"); enqueue("b1", "B");
        WorkQueue.Item a1 = claim("w1", 1, LEASE).get(0);
        assertEquals("a1", a1.messageId());
        Optional<WorkQueue.Item> next = queue.completeAndTakeNext(a1.seq(), "w1", LEASE).await();
        assertEquals("a2", next.orElseThrow().messageId(), "the next row of key A, skipping the older free row n0");
        assertEquals("w1", queue.leaseHolder(next.get().seq()).orElseThrow());
        assertEquals(Set.of("n0", "b1"), ids(claim("w2", 10, LEASE)), "w2 gets the rest; A is held by w1's fast path");
        Optional<WorkQueue.Item> a3 = queue.completeAndTakeNext(next.get().seq(), "w1", LEASE).await();
        assertEquals("a3", a3.orElseThrow().messageId());
        assertTrue(queue.completeAndTakeNext(a3.get().seq(), "w1", LEASE).await().isEmpty(), "key A is drained");
        assertEquals(2, queue.size(), "only n0 and b1 (leased by w2) remain");
    }

    @Test
    void fastPathOnANullKeyOrAnEmptyKeyJustCompletes() {
        enqueue("n1", null); enqueue("n2", null);
        WorkQueue.Item n1 = claim("w1", 1, LEASE).get(0);
        assertTrue(queue.completeAndTakeNext(n1.seq(), "w1", LEASE).await().isEmpty(), "no ordering, no fast path");
        assertEquals(1, queue.size());
    }

    @Test
    void fastPathOnAnUnknownSeqIsANoOp() {
        enqueue("a1", "A");
        assertTrue(queue.completeAndTakeNext(999L, "w1", LEASE).await().isEmpty());
        assertEquals(1, queue.size());
        assertTrue(queue.leaseHolder(999L).isEmpty());
        queue.complete(999L).await();
        assertEquals(1, queue.size());
    }

    @Test
    void fastPathStartsAFreshLease() {
        enqueue("a1", "A"); enqueue("a2", "A");
        WorkQueue.Item a1 = claim("w1", 1, Duration.ofSeconds(1)).get(0);
        clock.advance(Duration.ofMillis(900));
        WorkQueue.Item a2 = queue.completeAndTakeNext(a1.seq(), "w1", Duration.ofSeconds(1)).await().orElseThrow();
        clock.advance(Duration.ofMillis(900));
        assertTrue(claim("w2", 1, LEASE).isEmpty(), "a2's lease started at the fast path, not at a1's claim");
        clock.advance(Duration.ofMillis(200));
        assertEquals(a2.seq(), claim("w2", 1, LEASE).get(0).seq(), "and expires one lease after the fast path");
    }

    @Test
    void claimRespectsFlowAndMax() {
        enqueue("a", null); enqueue("b", null); enqueue("c", null);
        queue.enqueue("other", "u", 1, null).await();
        assertEquals(2, claim("w1", 2, LEASE).size());
        assertEquals(1, claim("w1", 10, LEASE).size());
        assertEquals("other", queue.claim("w1", "u", 10, LEASE).await().get(0).messageId());
    }
}
