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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("w1", queue.leaseHolder(first.get(0).seq()).orElseThrow());
        clock.advance(Duration.ofMillis(999));
        assertTrue(claim("w2", 1, Duration.ofSeconds(1)).isEmpty(), "still active at 999 ms");
        clock.advance(Duration.ofMillis(2));
        assertTrue(queue.leaseHolder(first.get(0).seq()).isEmpty(), "an expired lease has no holder");
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
    void aSameKeyRowInAnotherFlowNeitherBlocksTheClaimNorFeedsTheFastPath() {
        queue.enqueue("u-a0", "u", 1, "A").await();          // older key-A row, but in flow u
        enqueue("a1", "A"); enqueue("a2", "A");
        List<WorkQueue.Item> t = claim("w1", 10, LEASE);
        assertEquals(List.of("a1"), t.stream().map(WorkQueue.Item::messageId).toList(), "keys are scoped per flow: u's row does not make a1 'not the oldest'");
        Optional<WorkQueue.Item> next = queue.completeAndTakeNext(t.get(0).seq(), "w1", LEASE).await();
        assertEquals("a2", next.orElseThrow().messageId(), "the fast path stays inside flow t");
        assertEquals("u-a0", queue.claim("w2", "u", 10, LEASE).await().get(0).messageId(), "flow u is untouched");
    }

    // --- P1-08b (D-055): attempts as the fencing token, renew, hold

    @Test
    void claimAndFastPathCountAttempts() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item first = claim("w1", 10, LEASE).get(0);
        assertEquals("x1", first.messageId());
        assertEquals(1, first.attempts(), "a claim counts itself");
        clock.advance(LEASE);
        WorkQueue.Item again = claim("w2", 10, LEASE).get(0);
        assertEquals(first.seq(), again.seq());
        assertEquals(2, again.attempts(), "a re-claim after the lease ran out is the second attempt");
        WorkQueue.Item next = queue.completeAndTakeNext(again.seq(), "w2", LEASE).await().orElseThrow();
        assertEquals("x2", next.messageId());
        assertEquals(1, next.attempts(), "the fast path's claim counts as well");
        clock.advance(LEASE);
        assertEquals(2, claim("w3", 10, LEASE).get(0).attempts(), "and the row keeps counting from there");
    }

    @Test
    void renewIsFencedByAttempts() {
        enqueue("r1", null);
        WorkQueue.Item held = claim("w1", 1, LEASE).get(0);   // attempts 1, leased until +30 s
        clock.advance(Duration.ofSeconds(20));
        assertFalse(queue.renew(held.seq(), 2, LEASE).await(), "attempts that are not the row's: refused");
        assertFalse(queue.renew(held.seq(), 0, LEASE).await());
        clock.advance(Duration.ofSeconds(10));
        WorkQueue.Item taken = claim("w2", 1, LEASE).get(0);
        assertEquals(2, taken.attempts(), "the refused renewals left the lease where it was: free at +30 s");
        assertFalse(queue.renew(held.seq(), held.attempts(), LEASE).await(), "the old holder's renewal is fenced off by the new claim");
        assertEquals("w2", queue.leaseHolder(held.seq()).orElseThrow());

        clock.advance(Duration.ofSeconds(10));   // +40 s, w2's lease runs to +60 s
        assertTrue(queue.renew(taken.seq(), taken.attempts(), LEASE).await(), "the current holder renews");
        clock.advance(Duration.ofSeconds(30).minusMillis(1));
        assertTrue(claim("w3", 1, LEASE).isEmpty(), "renewed at +40 s for a whole lease: still w2's 1 ms before +70 s");
        assertEquals("w2", queue.leaseHolder(taken.seq()).orElseThrow());
        clock.advance(Duration.ofMillis(1));
        assertTrue(queue.leaseHolder(taken.seq()).isEmpty(), "and free at +70 s");

        assertTrue(queue.renew(taken.seq(), taken.attempts(), LEASE).await(), "an expired lease nobody re-claimed is revived");
        assertTrue(claim("w3", 1, LEASE).isEmpty(), "revived: not claimable");
        assertEquals("w2", queue.leaseHolder(taken.seq()).orElseThrow());

        assertTrue(queue.hold(taken.seq(), taken.attempts()).await());
        assertFalse(queue.renew(taken.seq(), taken.attempts(), LEASE).await(), "a held row is not renewed");
        queue.complete(taken.seq()).await();
        assertFalse(queue.renew(taken.seq(), taken.attempts(), LEASE).await(), "a completed row is not renewed");
        assertFalse(queue.renew(999L, 1, LEASE).await(), "nor an unknown one");
    }

    @Test
    void aHeldRowIsNeverClaimedAndBlocksItsKey() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("n1", null);
        WorkQueue.Item a1 = claim("w1", 1, LEASE).get(0);
        assertEquals("a1", a1.messageId());
        assertFalse(queue.hold(a1.seq(), 0).await(), "fenced: attempts that are not the row's hold nothing");
        clock.advance(LEASE);
        WorkQueue.Item again = claim("w2", 1, LEASE).get(0);
        assertEquals(a1.seq(), again.seq(), "the refused hold left the row claimable");
        assertFalse(queue.hold(a1.seq(), a1.attempts()).await(), "the old holder cannot hold what was claimed again");
        assertTrue(queue.hold(again.seq(), again.attempts()).await(), "the current holder holds it");

        clock.advance(LEASE);
        List<WorkQueue.Item> rest = claim("w3", 10, LEASE);
        assertEquals(List.of("n1"), rest.stream().map(WorkQueue.Item::messageId).toList(),
                "a1 is held and a2 waits behind it; the null-key row is claimable");
        queue.complete(rest.get(0).seq()).await();
        for (int i = 0; i < 3; i++) {
            clock.advance(LEASE);
            assertTrue(claim("w3", 10, LEASE).isEmpty(), "never claimed again, lease after lease");
        }
        assertEquals(2, queue.size(), "the held row and the row behind it stay");
        assertFalse(queue.hold(rest.get(0).seq(), rest.get(0).attempts()).await(), "a completed row cannot be held");
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
