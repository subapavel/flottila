package io.flottila.core.queue;

import io.flottila.core.record.Page;
import io.flottila.testkit.FakeClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The three proto-m1 WorkQueueTest cases in intent (lease expiry on the fake clock) plus the same-key fast path (ARCHITECTURE §7).
 * P1-09c1 (D-067): the held rows a page at a time, and release (keeps attempts, clears the hold and the lease).
 */
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
        WorkQueue.Item a1 = w1.stream().filter(i -> i.messageId().equals("a1")).findFirst().orElseThrow();
        assertTrue(queue.complete(a1.seq(), a1.attempts()).await(), "completed by its holder");
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
        assertTrue(queue.complete(got.get(0).seq(), got.get(0).attempts()).await(), "true: removed");
        assertEquals(0, queue.size());
        assertTrue(claim("w1", 1, LEASE).isEmpty());
    }

    @Test
    void sameKeyFastPathHandsTheNextRowOfTheKeyToTheFinishingWorker() {
        enqueue("a1", "A"); enqueue("n0", null); enqueue("a2", "A"); enqueue("a3", "A"); enqueue("b1", "B");
        WorkQueue.Item a1 = claim("w1", 1, LEASE).get(0);
        assertEquals("a1", a1.messageId());
        WorkQueue.Completion done = queue.completeAndTakeNext(a1.seq(), a1.attempts(), "w1", LEASE).await();
        assertTrue(done.completed(), "a1 completed by its holder");
        Optional<WorkQueue.Item> next = done.next();
        assertEquals("a2", next.orElseThrow().messageId(), "the next row of key A, skipping the older free row n0");
        assertEquals("w1", queue.leaseHolder(next.get().seq()).orElseThrow());
        assertEquals(Set.of("n0", "b1"), ids(claim("w2", 10, LEASE)), "w2 gets the rest; A is held by w1's fast path");
        Optional<WorkQueue.Item> a3 = queue.completeAndTakeNext(next.get().seq(), next.get().attempts(), "w1", LEASE).await().next();
        assertEquals("a3", a3.orElseThrow().messageId());
        assertEquals(WorkQueue.Completion.done(Optional.empty()), queue.completeAndTakeNext(a3.get().seq(), a3.get().attempts(), "w1", LEASE).await(),
                "a3 completed; key A is drained");
        assertEquals(2, queue.size(), "only n0 and b1 (leased by w2) remain");
    }

    @Test
    void fastPathOnANullKeyOrAnEmptyKeyJustCompletes() {
        enqueue("n1", null); enqueue("n2", null);
        WorkQueue.Item n1 = claim("w1", 1, LEASE).get(0);
        assertEquals(WorkQueue.Completion.done(Optional.empty()), queue.completeAndTakeNext(n1.seq(), n1.attempts(), "w1", LEASE).await(),
                "completed; no ordering, no fast path");
        assertEquals(1, queue.size());
    }

    @Test
    void fastPathOnAnUnknownSeqIsANoOp() {
        enqueue("a1", "A");
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(999L, 1, "w1", LEASE).await(), "unknown: nothing completed, nothing taken");
        assertEquals(1, queue.size());
        assertTrue(queue.leaseHolder(999L).isEmpty());
        assertFalse(queue.complete(999L, 1).await(), "unknown: false");
        assertEquals(1, queue.size());
    }

    @Test
    void fastPathStartsAFreshLease() {
        enqueue("a1", "A"); enqueue("a2", "A");
        WorkQueue.Item a1 = claim("w1", 1, Duration.ofSeconds(1)).get(0);
        clock.advance(Duration.ofMillis(900));
        WorkQueue.Item a2 = queue.completeAndTakeNext(a1.seq(), a1.attempts(), "w1", Duration.ofSeconds(1)).await().next().orElseThrow();
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
        Optional<WorkQueue.Item> next = queue.completeAndTakeNext(t.get(0).seq(), t.get(0).attempts(), "w1", LEASE).await().next();
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
        WorkQueue.Item next = queue.completeAndTakeNext(again.seq(), again.attempts(), "w2", LEASE).await().next().orElseThrow();
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
        assertTrue(queue.release(taken.seq()).await(), "D-074: a held row leaves only through release, claim, complete");
        WorkQueue.Item last = claim("w3", 1, LEASE).get(0);
        assertEquals(3, last.attempts(), "fixture");
        assertTrue(queue.complete(last.seq(), last.attempts()).await(), "fixture: completed by its holder");
        assertFalse(queue.renew(last.seq(), last.attempts(), LEASE).await(), "a completed row is not renewed");
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
        assertTrue(queue.complete(rest.get(0).seq(), rest.get(0).attempts()).await(), "fixture");
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

    /**
     * P1-09d2a (D-063): {@code hasItems(flow)} is true while any row of the flow exists — queued, leased, left to a lease that ran
     * out (backing off until the next claim) or held — and false once only completed rows were the flow's; another flow's rows,
     * one whose id starts with this flow's included, never count. Each true is asserted with only that kind of row present.
     */
    @Test
    void hasItemsSeesQueuedLeasedHeldNotCompletedNorOtherFlows() {
        String flow = "shop/audit";
        String other = "shop/audit-eu";
        assertFalse(queue.hasItems(flow).await(), "an empty queue");
        queue.enqueue("o1", other, 1, null).await();
        assertFalse(queue.hasItems(flow).await(), "only another flow's row, whose id starts with this flow's");
        assertTrue(queue.hasItems(other).await(), "witness: that flow sees its own row");

        queue.enqueue("q1", flow, 1, "K").await();
        assertTrue(queue.hasItems(flow).await(), "a queued row, due");
        WorkQueue.Item first = queue.claim("w1", flow, 1, LEASE).await().get(0);
        assertEquals("q1", first.messageId(), "fixture");
        assertTrue(queue.hasItems(flow).await(), "a leased row");
        clock.advance(LEASE.plusSeconds(1));
        assertTrue(queue.leaseHolder(first.seq()).isEmpty(), "fixture: the lease ran out, the row waits for its next claim");
        assertTrue(queue.hasItems(flow).await(), "a row left to its lease that ran out (backing off)");
        WorkQueue.Item again = queue.claim("w2", flow, 1, LEASE).await().get(0);
        assertTrue(queue.hold(again.seq(), again.attempts()).await(), "fixture: held");
        clock.advance(LEASE.multipliedBy(3));
        assertTrue(queue.claim("w3", flow, 10, LEASE).await().isEmpty(), "fixture: a held row is never claimed again");
        assertTrue(queue.hasItems(flow).await(), "a held row");

        assertFalse(queue.complete(again.seq(), again.attempts()).await(), "D-074: a held row is not completed, not even by its holder");
        assertTrue(queue.hasItems(flow).await(), "still a held row");
        assertTrue(queue.release(again.seq()).await(), "fixture: a held row leaves through release, claim, complete");
        WorkQueue.Item last = queue.claim("w3", flow, 1, LEASE).await().get(0);
        assertTrue(queue.complete(last.seq(), last.attempts()).await(), "fixture");
        assertFalse(queue.hasItems(flow).await(), "only completed rows: no items");
        queue.enqueue("q2", flow, 1, null).await();
        queue.enqueue("q3", flow, 1, "K").await();
        WorkQueue.Item q2 = queue.claim("w1", flow, 1, LEASE).await().get(0);
        assertTrue(queue.completeAndTakeNext(q2.seq(), q2.attempts(), "w1", LEASE).await().completed(), "fixture");
        assertTrue(queue.hasItems(flow).await(), "one row completed, one still queued");
        for (WorkQueue.Item left : queue.claim("w1", flow, 10, LEASE).await()) assertTrue(queue.complete(left.seq(), left.attempts()).await(), "fixture");
        assertFalse(queue.hasItems(flow).await(), "every row completed");
        assertTrue(queue.hasItems(other).await(), "the other flow's row stays its own");
    }

    /** P1-09d2a review (D-063): both implementations refuse a null flow at the call, as the interface says. */
    @Test
    void hasItemsOfANullFlowThrows() {
        queue.enqueue("n1", "shop/audit", 1, null).await();
        assertThrows(NullPointerException.class, () -> queue.hasItems(null), "a null flow is a NullPointerException at the call, never false");
        assertTrue(queue.hasItems("shop/audit").await(), "witness: the queue answers for a flow");
    }

    // --- P1-09c1 (D-067): held(after, limit) a page at a time in seq order, release(seq)

    static final Instant T0 = Instant.EPOCH;

    /** Every held row, page after page of {@code limit}; a page with a next cursor is full, a page reached by a cursor is not empty. */
    static List<WorkQueue.Held> walk(WorkQueue q, int limit) {
        List<WorkQueue.Held> all = new ArrayList<>();
        Optional<String> after = Optional.empty();
        for (int pages = 0; pages <= 1_000; pages++) {
            Page<WorkQueue.Held> page = q.held(after, limit).await();
            assertTrue(page.items().size() <= limit, "at most limit items: " + page);
            if (after.isPresent()) assertFalse(page.items().isEmpty(), "a cursor is handed out only when another item exists: " + page);
            all.addAll(page.items());
            if (page.next().isEmpty()) return all;
            assertEquals(limit, page.items().size(), "a page with a next cursor is full: " + page);
            after = page.next();
        }
        return fail("no last page within 1000 pages: " + all);
    }

    /** Refused with IllegalArgumentException (the admin API's 400), thrown at the call or as the failed future's cause. */
    static void refused(org.junit.jupiter.api.function.Executable call, String what) {
        Throwable t = assertThrows(Throwable.class, call, what + " is refused");
        assertInstanceOf(IllegalArgumentException.class, t, what + ": " + t);
    }

    static List<String> heldIds(List<WorkQueue.Held> held) {
        return held.stream().map(h -> h.item().messageId()).toList();
    }

    /** The one row {@code claimed} handed out for {@code id}. */
    static WorkQueue.Item one(List<WorkQueue.Item> claimed, String id) {
        return claimed.stream().filter(i -> i.messageId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id + " not in " + claimed));
    }

    /**
     * Rows held in two flows, a queued row behind a held one and a leased row between them: the listing holds only the held rows,
     * across flows, in seq order, each as last handed out with the instant it was held; pages of 2 and 3 walk them, the last page
     * has no cursor (an exact fit included), and the cursor is a position: releasing the rows up to the cursor skips nothing after it.
     */
    @Test
    void heldListsOnlyHeldRowsInSeqOrderAPageAtATime() {
        queue.enqueue("h1", "t", 1, "A").await();
        queue.enqueue("q1", "t", 1, "A").await();       // behind h1: never handed out
        queue.enqueue("h2", "t", 2, null).await();
        queue.enqueue("l1", "t", 1, null).await();      // leased, never held
        queue.enqueue("h3", "u", 3, null).await();      // another flow, held at its second attempt
        queue.enqueue("h4", "t", 1, "B").await();
        assertEquals(new Page<WorkQueue.Held>(List.of(), Optional.empty()), queue.held(Optional.empty(), 10).await(), "nothing held yet: an empty last page");

        List<WorkQueue.Item> t = claim("w1", 10, LEASE);
        assertEquals(List.of("h1", "h2", "l1", "h4"), t.stream().map(WorkQueue.Item::messageId).toList(), "fixture: q1 waits behind h1");
        WorkQueue.Item u1 = queue.claim("w2", "u", 1, Duration.ofSeconds(1)).await().get(0);
        clock.advance(Duration.ofSeconds(1));
        WorkQueue.Item u2 = queue.claim("w2", "u", 1, LEASE).await().get(0);
        assertEquals(u1.seq(), u2.seq(), "fixture");
        assertEquals(2, u2.attempts(), "fixture");

        Map<String, Instant> heldAt = new HashMap<>();
        for (WorkQueue.Item it : List.of(one(t, "h4"), u2, one(t, "h1"), one(t, "h2"))) {   // held out of seq order, a second apart
            clock.advance(Duration.ofSeconds(1));
            assertTrue(queue.hold(it.seq(), it.attempts()).await(), "fixture: " + it.messageId() + " held");
            heldAt.put(it.messageId(), clock.instant());
        }
        clock.advance(Duration.ofSeconds(1));

        List<WorkQueue.Held> all = queue.held(Optional.empty(), Page.MAX_LIMIT).await().items();
        List<WorkQueue.Held> expected = List.of(
                new WorkQueue.Held(one(t, "h1"), heldAt.get("h1")),
                new WorkQueue.Held(one(t, "h2"), heldAt.get("h2")),
                new WorkQueue.Held(u2, heldAt.get("h3")),
                new WorkQueue.Held(one(t, "h4"), heldAt.get("h4")));
        assertEquals(expected, all, "only held rows, every flow's, in seq order, as last handed out (h3 at attempt 2), heldAt = the hold's instant");
        assertEquals(Optional.empty(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().next());

        Page<WorkQueue.Held> first = queue.held(Optional.empty(), 2).await();
        assertEquals(List.of("h1", "h2"), heldIds(first.items()));
        assertTrue(first.next().isPresent(), "two more held rows");
        Page<WorkQueue.Held> second = queue.held(first.next(), 2).await();
        assertEquals(List.of("h3", "h4"), heldIds(second.items()));
        assertEquals(Optional.empty(), second.next(), "exactly limit rows left: no cursor to an empty page");
        assertEquals(expected, walk(queue, 3), "pages of 3: h1 h2 h3, then h4");
        assertEquals(expected, walk(queue, 1));

        Page<WorkQueue.Held> page = queue.held(Optional.empty(), 2).await();
        assertTrue(queue.release(one(t, "h1").seq()).await(), "fixture");
        assertTrue(queue.release(one(t, "h2").seq()).await(), "fixture: the cursor's own row released");
        assertEquals(List.of("h3", "h4"), heldIds(queue.held(page.next(), 2).await().items()),
                "the cursor is a position in seq order, never an offset: the rows released before it skip nothing");
    }

    @Test
    void heldRefusesABadLimitOrCursor() {
        enqueue("h1", null); enqueue("h2", null);
        for (WorkQueue.Item it : claim("w1", 10, LEASE)) assertTrue(queue.hold(it.seq(), it.attempts()).await(), "fixture");
        for (int limit : new int[] {0, -1, Page.MAX_LIMIT + 1, Integer.MIN_VALUE}) refused(() -> queue.held(Optional.empty(), limit).await(), "limit " + limit);
        String notASeq = Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", notASeq)) refused(() -> queue.held(Optional.of(cursor), 1).await(), "cursor " + cursor);
        assertEquals(List.of("h1"), heldIds(queue.held(Optional.empty(), 1).await().items()), "1 is a limit");
        assertEquals(List.of("h1", "h2"), heldIds(queue.held(Optional.empty(), Page.MAX_LIMIT).await().items()), "500 is a limit");
        Optional<String> next = queue.held(Optional.empty(), 1).await().next();
        assertEquals(List.of("h2"), heldIds(queue.held(next, 1).await().items()), "the cursor the store handed out is one it takes");
    }

    /**
     * The row was handed out twice and held by the second claim, whose lease still runs: release answers true, the row is no longer
     * listed, its lease is gone (claimable at once, no clock advance), and it keeps its attempts — the next claim is the third.
     */
    @Test
    void releaseClearsHoldAndLeaseKeepsAttempts() {
        enqueue("r1", "K"); enqueue("r2", "K");
        WorkQueue.Item first = claim("w1", 10, LEASE).get(0);
        clock.advance(LEASE);
        WorkQueue.Item again = claim("w2", 10, LEASE).get(0);
        assertEquals(first.seq(), again.seq(), "fixture");
        assertEquals(2, again.attempts(), "fixture");
        assertTrue(queue.hold(again.seq(), again.attempts()).await(), "fixture: held by its current holder");
        assertEquals("w2", queue.leaseHolder(again.seq()).orElseThrow(), "fixture: the hold left w2's lease running (to +60 s)");
        assertTrue(claim("w3", 10, LEASE).isEmpty(), "fixture: held, and r2 waits behind it");

        assertTrue(queue.release(again.seq()).await(), "a held row is released");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items(), "no longer held");
        assertTrue(queue.leaseHolder(again.seq()).isEmpty(), "the lease is cleared with the hold: no holder while w2's lease would still run");
        assertEquals(2, queue.snapshot().get(0).attempts(), "attempts kept: the row still counts its two hand-outs");
        List<WorkQueue.Item> next = claim("w3", 10, LEASE);
        assertEquals(List.of(new WorkQueue.Item(again.seq(), "r1", "t", 1, "K", 3)), next,
                "claimable at once, before r2, and its next hand-out is the third attempt");
    }

    @Test
    void releaseOfAnUnheldOrUnknownSeqIsFalse() {
        enqueue("l1", null); enqueue("q1", "K"); enqueue("h1", null);
        WorkQueue.Item l1 = claim("w1", 1, LEASE).get(0);
        assertEquals("l1", l1.messageId(), "fixture: leased, never held");
        long q1 = queue.snapshot().get(1).seq();
        assertFalse(queue.release(999L).await(), "an unknown seq");
        assertFalse(queue.release(q1).await(), "a queued row never handed out");
        assertFalse(queue.release(l1.seq()).await(), "a leased row that is not held");
        assertEquals("w1", queue.leaseHolder(l1.seq()).orElseThrow(), "the refused release left the lease where it was");
        assertEquals(List.of(1, 0, 0), queue.snapshot().stream().map(WorkQueue.Item::attempts).toList(), "and the attempts");
        List<WorkQueue.Item> rest = claim("w2", 10, LEASE);
        assertEquals(List.of("q1", "h1"), rest.stream().map(WorkQueue.Item::messageId).toList(), "the refused release changed nothing: q1 claimable as before");
        WorkQueue.Item h1 = one(rest, "h1");
        assertTrue(queue.hold(h1.seq(), h1.attempts()).await(), "fixture");
        assertTrue(queue.release(h1.seq()).await(), "witness: a held row is released");
        assertFalse(queue.release(h1.seq()).await(), "released already: no longer held");
        assertTrue(queue.complete(h1.seq(), h1.attempts()).await(),
                "D-074: a released row keeps its attempts, so its last holder's token stays current until the next claim");
        assertFalse(queue.release(h1.seq()).await(), "completed: unknown");
    }

    /** a1 held blocks a2 behind it; released, a1 is the key's next hand-out, and a2 follows only once a1 completed. */
    @Test
    void aReleasedRowIsClaimedBeforeTheRowsBehindItsKey() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("b1", "B");
        List<WorkQueue.Item> first = claim("w1", 10, LEASE);
        assertEquals(List.of("a1", "b1"), first.stream().map(WorkQueue.Item::messageId).toList(), "fixture");
        WorkQueue.Item a1 = one(first, "a1");
        assertTrue(queue.hold(a1.seq(), a1.attempts()).await(), "fixture");
        assertTrue(queue.complete(one(first, "b1").seq(), one(first, "b1").attempts()).await(), "fixture");
        clock.advance(LEASE);
        assertTrue(claim("w2", 10, LEASE).isEmpty(), "held: a1 is never claimed and a2 waits behind it");

        assertTrue(queue.release(a1.seq()).await());
        List<WorkQueue.Item> after = claim("w2", 10, LEASE);
        assertEquals(List.of(new WorkQueue.Item(a1.seq(), "a1", "t", 1, "A", 2)), after, "the released row comes first; a2 stays behind it");
        assertTrue(claim("w3", 10, LEASE).isEmpty(), "a1 leased again: key A blocked by its live lease");
        WorkQueue.Item a2 = queue.completeAndTakeNext(a1.seq(), after.get(0).attempts(), "w2", LEASE).await().next().orElseThrow();
        assertEquals("a2", a2.messageId(), "a2 once a1 completed");
        assertEquals(1, a2.attempts());
    }

    // --- Q-1 (D-074): complete and the fast path are fenced by the attempts token, as renew and hold are

    /** Every row in seq order with its lease holder: what a refused call must leave as it was. */
    private Map<Long, String> state() {
        Map<Long, String> state = new TreeMap<>();
        for (WorkQueue.Item it : queue.snapshot()) state.put(it.seq(), it + " leased to " + queue.leaseHolder(it.seq()).orElse("nobody"));
        return state;
    }

    /**
     * w1 was handed x1 at attempt 1, its lease ran out and w2 claimed x1 again (attempt 2): w1's complete is stale — false, the row
     * stays w2's, x2 still waits behind it. w2's complete removes it (true); a second complete, or one of an unknown seq, is false.
     */
    @Test
    void completeIsFencedByAttempts() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item mine = claim("w1", 10, LEASE).get(0);
        clock.advance(LEASE);
        WorkQueue.Item theirs = claim("w2", 10, LEASE).get(0);
        assertEquals(mine.seq(), theirs.seq(), "fixture: claimed again");
        assertEquals(2, theirs.attempts(), "fixture");
        Map<Long, String> before = state();

        assertFalse(queue.complete(mine.seq(), mine.attempts()).await(), "the old holder's complete is stale: false");
        assertEquals(before, state(), "and removes nothing: x1 stays w2's, at attempt 2, x2 behind it");
        assertTrue(claim("w3", 10, LEASE).isEmpty(), "x1 still leased to w2, x2 still behind it");
        assertFalse(queue.complete(theirs.seq(), theirs.attempts() + 1).await(), "attempts ahead of the row's: false");
        assertEquals(before, state());

        assertTrue(queue.complete(theirs.seq(), theirs.attempts()).await(), "the current holder's complete removes the row: true");
        assertEquals(List.of("x2"), queue.snapshot().stream().map(WorkQueue.Item::messageId).toList());
        assertFalse(queue.complete(theirs.seq(), theirs.attempts()).await(), "completed already: false");
        assertFalse(queue.complete(999L, 1).await(), "an unknown seq: false");
        assertEquals(List.of("x2"), claim("w3", 10, LEASE).stream().map(WorkQueue.Item::messageId).toList(), "x2 once x1 is gone");
    }

    /**
     * w1's fast path on x1, claimed again by w2 meanwhile: STALE — x1 is not completed and x2 is not taken, nothing changes; w2's
     * fast path on x1 completes it and hands x2 to w2; a fast path on a completed row is STALE too.
     */
    @Test
    void aStaleFastPathCompletesNothingAndTakesNothing() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item mine = claim("w1", 10, LEASE).get(0);
        clock.advance(LEASE);
        WorkQueue.Item theirs = claim("w2", 10, LEASE).get(0);
        assertEquals(mine.seq(), theirs.seq(), "fixture: claimed again");
        Map<Long, String> before = state();

        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(mine.seq(), mine.attempts(), "w1", LEASE).await(),
                "the old holder's fast path is stale");
        assertEquals(before, state(), "x1 not completed, x2 not taken: still at attempt 0, leased to nobody");

        WorkQueue.Completion done = queue.completeAndTakeNext(theirs.seq(), theirs.attempts(), "w2", LEASE).await();
        WorkQueue.Item x2 = queue.snapshot().get(0);
        assertEquals(WorkQueue.Completion.done(Optional.of(new WorkQueue.Item(x2.seq(), "x2", "t", 1, "K", 1))), done,
                "the current holder's fast path completes x1 and takes x2");
        assertEquals("w2", queue.leaseHolder(x2.seq()).orElseThrow());
        Map<Long, String> after = state();
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(theirs.seq(), theirs.attempts(), "w2", LEASE).await(),
                "completed already: stale");
        assertEquals(after, state());
    }

    /**
     * hold is fenced by the attempts as renew and complete are: the old holder's hold of a row claimed again is false and holds
     * nothing — the new holder completes the row — and a hold of a completed row is false.
     */
    @Test
    void holdIsFencedByAttempts() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item mine = claim("w1", 10, LEASE).get(0);
        clock.advance(LEASE);
        WorkQueue.Item theirs = claim("w2", 10, LEASE).get(0);
        assertEquals(mine.seq(), theirs.seq(), "fixture: claimed again");
        Map<Long, String> before = state();

        assertFalse(queue.hold(mine.seq(), mine.attempts()).await(), "the old holder's hold is stale: false");
        assertFalse(queue.hold(theirs.seq(), theirs.attempts() + 1).await(), "attempts ahead of the row's: false");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items(), "nothing held");
        assertEquals(before, state());
        assertTrue(queue.complete(theirs.seq(), theirs.attempts()).await(), "not held: the current holder completes it");
        assertFalse(queue.hold(theirs.seq(), theirs.attempts()).await(), "a completed row is not held");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    /**
     * A held row is completed by nobody, its holder included: complete is false and the fast path STALE, the row stays held and
     * x2 behind it; it leaves only through release, a claim and the new holder's complete — the old token is stale by then.
     */
    @Test
    void aHeldRowIsNotCompletedByItsHolder() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item x1 = claim("w1", 10, LEASE).get(0);
        assertTrue(queue.hold(x1.seq(), x1.attempts()).await(), "fixture: held by its holder");
        Map<Long, String> before = state();

        assertFalse(queue.complete(x1.seq(), x1.attempts()).await(), "a held row is not completed, not even by its holder");
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(x1.seq(), x1.attempts(), "w1", LEASE).await(),
                "nor through the fast path, which takes nothing");
        assertEquals(before, state(), "x1 and x2 as they were");
        assertEquals(List.of(x1), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(WorkQueue.Held::item).toList(),
                "x1 still held");

        assertTrue(queue.release(x1.seq()).await(), "fixture");
        WorkQueue.Item again = claim("w2", 10, LEASE).get(0);
        assertEquals(new WorkQueue.Item(x1.seq(), "x1", "t", 1, "K", 2), again, "fixture: the released row comes first");
        assertFalse(queue.complete(x1.seq(), x1.attempts()).await(), "after the claim the old token is stale");
        assertTrue(queue.complete(again.seq(), again.attempts()).await(), "the new holder completes it");
        assertEquals(List.of("x2"), queue.snapshot().stream().map(WorkQueue.Item::messageId).toList());
    }

    /**
     * Attempts 0 is the token of a row never handed out, and it matches nothing: renew, hold, complete and the fast path on q1 are
     * all refused and change nothing. Witness: q1 is then handed out at attempt 1, and that token renews and completes.
     */
    @Test
    void aTokenOfZeroMatchesNoRow() {
        enqueue("q1", "K"); enqueue("q2", "K");
        long q1 = queue.snapshot().get(0).seq();
        Map<Long, String> before = state();

        assertFalse(queue.renew(q1, 0, LEASE).await(), "renew with token 0");
        assertFalse(queue.hold(q1, 0).await(), "hold with token 0");
        assertFalse(queue.complete(q1, 0).await(), "complete with token 0");
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(q1, 0, "w1", LEASE).await(), "fast path with token 0");
        assertEquals(before, state(), "nothing renewed, held, completed or taken");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items());

        WorkQueue.Item first = claim("w1", 10, LEASE).get(0);
        assertEquals(new WorkQueue.Item(q1, "q1", "t", 1, "K", 1), first, "witness: q1 untouched, handed out at attempt 1");
        assertTrue(queue.renew(q1, 1, LEASE).await(), "witness: its token renews");
        assertTrue(queue.complete(q1, 1).await(), "and completes");
    }

    /** The unfenced forms are gone from the contract: every completion names the attempts it holds (Q-1). */
    @Test
    void theUnfencedCompleteIsGone() {
        assertThrows(NoSuchMethodException.class, () -> WorkQueue.class.getMethod("complete", long.class), "complete(seq)");
        assertThrows(NoSuchMethodException.class, () -> WorkQueue.class.getMethod("completeAndTakeNext", long.class, String.class, Duration.class),
                "completeAndTakeNext(seq, workerId, lease)");
        // witness: the same lookup finds the fenced forms
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> WorkQueue.class.getMethod("complete", long.class, int.class));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> WorkQueue.class.getMethod("completeAndTakeNext", long.class, int.class, String.class, Duration.class));
    }

    /** The shapes of a fast path's answer: STALE (nothing completed, nothing next) or done(next); never not completed with a next item. */
    @Test
    void aCompletionWithoutCompletingCarriesNoNextItem() {
        WorkQueue.Item next = new WorkQueue.Item(2, "a2", "t", 1, "A", 1);
        assertThrows(IllegalArgumentException.class, () -> new WorkQueue.Completion(false, Optional.of(next)),
                "not completed, yet a next item: refused");
        assertThrows(NullPointerException.class, () -> new WorkQueue.Completion(true, null), "next is never null");
        assertThrows(NullPointerException.class, () -> new WorkQueue.Completion(false, null), "next is never null");
        assertThrows(NullPointerException.class, () -> WorkQueue.Completion.done(null), "next is never null");
        assertEquals(new WorkQueue.Completion(false, Optional.empty()), WorkQueue.Completion.STALE, "STALE: not completed, nothing next");
        assertEquals(new WorkQueue.Completion(true, Optional.of(next)), WorkQueue.Completion.done(Optional.of(next)), "done(next)");
        assertEquals(new WorkQueue.Completion(true, Optional.empty()), WorkQueue.Completion.done(Optional.empty()), "completed, nothing next");
        assertTrue(new WorkQueue.Completion(true, Optional.of(next)).completed(), "witness: completed with a next item is a completion");
    }

    // --- the property: held rows block their key until released (D-067)

    static final List<String> HELD_KEYS = java.util.Arrays.asList("A", "B", null);

    sealed interface Act permits Take, HoldIt, ReleaseIt, Finish, Wait {}
    /** Claims up to {@code max} rows of flow f. */
    record Take(int max) implements Act {}
    /** Holds an item handed out earlier, with the attempts it was handed out with, whatever became of it since. */
    record HoldIt(int pick) implements Act {}
    /** Releases any seq ever enqueued — held, leased, queued, completed — or an unknown one. */
    record ReleaseIt(int pick) implements Act {}
    /** Completes an item handed out earlier with the attempts it was handed out with, whatever became of it since (Q-1: fenced). */
    record Finish(int pick) implements Act {}
    record Wait(int seconds) implements Act {}

    @Provide
    Arbitrary<List<Integer>> heldKeys() {
        return Arbitraries.integers().between(0, 2).list().ofMinSize(1).ofMaxSize(12);
    }

    @Provide
    Arbitrary<List<Act>> heldActs() {
        Arbitrary<Act> take = Arbitraries.integers().between(1, 4).map(Take::new);
        Arbitrary<Act> hold = Arbitraries.integers().between(0, 40).map(HoldIt::new);
        Arbitrary<Act> release = Arbitraries.integers().between(0, 40).map(ReleaseIt::new);
        Arbitrary<Act> finish = Arbitraries.integers().between(0, 40).map(Finish::new);
        Arbitrary<Act> wait = Arbitraries.integers().between(1, 15).map(Wait::new);
        return Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(4, take), net.jqwik.api.Tuple.of(3, hold), net.jqwik.api.Tuple.of(3, release),
                net.jqwik.api.Tuple.of(2, finish), net.jqwik.api.Tuple.of(1, wait)).list().ofMinSize(1).ofMaxSize(60);
    }

    /**
     * Any interleaving of claims, holds, releases, completions and time: every claim hands out exactly the rows a model says are
     * eligible (not held, no live lease, the oldest remaining row of its key, held rows included; seq order, up to max) — so a held
     * row is never handed out and blocks the rows behind it, and a released row is claimable at once, before them; each hand-out counts
     * one more attempt on top of what the row kept through its release; a hold, a release and a completion answer exactly when the
     * model says (Q-1: a completion removes only the current holder's row, never a held one);
     * after every step the held listing, walked a page at a time, is the model's held rows in seq order with their hold instant. At
     * the end every held row is released and the queue drains to empty: nothing stays blocked once released.
     */
    @Property(tries = 300)
    void heldRowsBlockTheirKeyUntilReleased(@ForAll("heldKeys") List<Integer> keyIndexes, @ForAll("heldActs") List<Act> acts,
                                            @ForAll @IntRange(min = 1, max = 3) int pageSize) {
        FakeClock clock = FakeClock.at(T0);
        InMemoryWorkQueue q = new InMemoryWorkQueue(clock);
        for (int i = 0; i < keyIndexes.size(); i++) q.enqueue("m" + i, "f", 1, HELD_KEYS.get(keyIndexes.get(i))).await();
        Map<Long, WorkQueue.Item> enqueued = new HashMap<>();
        TreeMap<Long, String> rows = new TreeMap<>();            // seq → key (null: no ordering), the rows not completed
        Map<Long, Integer> attempts = new HashMap<>();
        Map<Long, Instant> until = new HashMap<>();
        TreeMap<Long, Instant> heldAt = new TreeMap<>();
        List<WorkQueue.Item> handed = new ArrayList<>();
        List<Long> seqs = new ArrayList<>();
        for (WorkQueue.Item it : q.snapshot()) {
            enqueued.put(it.seq(), it);
            rows.put(it.seq(), it.partitionKey());
            attempts.put(it.seq(), 0);
            seqs.add(it.seq());
        }
        HeldModel model = new HeldModel(clock, rows, until, heldAt);

        for (int n = 0; n < acts.size(); n++) {
            Act act = acts.get(n);
            String at = "act " + n + " " + act + " of " + acts + ", keys " + keyIndexes;
            switch (act) {
                case Take take -> {
                    List<Long> expected = model.eligible(take.max());
                    List<WorkQueue.Item> got = q.claim("w", "f", take.max(), LEASE).await();
                    assertEquals(expected, got.stream().map(WorkQueue.Item::seq).toList(), at + ": the claim hands out exactly the eligible rows");
                    for (WorkQueue.Item it : got) {
                        int counted = attempts.get(it.seq()) + 1;
                        assertEquals(counted, it.attempts(), at + ": attempts count every hand-out, kept through a release: " + it);
                        attempts.put(it.seq(), counted);
                        until.put(it.seq(), clock.instant().plus(LEASE));
                        handed.add(it);
                    }
                }
                case HoldIt hold -> {
                    if (handed.isEmpty()) continue;
                    WorkQueue.Item it = handed.get(hold.pick() % handed.size());
                    boolean expected = rows.containsKey(it.seq()) && !heldAt.containsKey(it.seq()) && attempts.get(it.seq()) == it.attempts();
                    assertEquals(expected, q.hold(it.seq(), it.attempts()).await(), at + ": hold of " + it);
                    if (expected) heldAt.put(it.seq(), clock.instant());
                }
                case ReleaseIt release -> {
                    int i = release.pick() % (seqs.size() + 1);
                    long seq = i == seqs.size() ? 10_000L : seqs.get(i);
                    boolean expected = heldAt.containsKey(seq);
                    assertEquals(expected, q.release(seq).await(), at + ": release of " + seq + " answers true exactly for a held row");
                    if (expected) {
                        heldAt.remove(seq);
                        until.remove(seq);
                    }
                }
                case Finish finish -> {
                    if (handed.isEmpty()) continue;
                    WorkQueue.Item it = handed.get(finish.pick() % handed.size());
                    long seq = it.seq();
                    boolean expected = rows.containsKey(seq) && !heldAt.containsKey(seq) && attempts.get(seq) == it.attempts();
                    assertEquals(expected, q.complete(seq, it.attempts()).await(), at + ": complete of " + it + " removes exactly the current holder's row");
                    if (expected) {
                        rows.remove(seq);
                        until.remove(seq);
                    }
                }
                case Wait wait -> clock.advance(Duration.ofSeconds(wait.seconds()));
            }
            List<WorkQueue.Held> expectedHeld = new ArrayList<>();
            heldAt.forEach((seq, instant) -> {
                WorkQueue.Item e = enqueued.get(seq);
                expectedHeld.add(new WorkQueue.Held(new WorkQueue.Item(seq, e.messageId(), "f", 1, e.partitionKey(), attempts.get(seq)), instant));
            });
            assertEquals(expectedHeld, walk(q, pageSize), at + ": the held listing, pages of " + pageSize);
        }

        for (long seq : List.copyOf(heldAt.keySet())) {
            assertTrue(q.release(seq).await(), "release of held " + seq + " at the end");
            heldAt.remove(seq);
            until.remove(seq);
        }
        clock.advance(LEASE);
        int rounds = 0;
        while (!rows.isEmpty()) {
            assertTrue(rounds++ <= keyIndexes.size(), "every released key drains, one row per key and round: " + rows + " after " + acts);
            List<Long> expected = model.eligible(Integer.MAX_VALUE);
            List<WorkQueue.Item> got = q.claim("w", "f", 1_000, LEASE).await();
            assertEquals(expected, got.stream().map(WorkQueue.Item::seq).toList(), "drain round " + rounds + " after " + acts);
            assertFalse(got.isEmpty(), "rows remain, so a round hands one out: " + rows);
            for (WorkQueue.Item it : got) {
                assertTrue(q.complete(it.seq(), it.attempts()).await(), "drain round " + rounds + ": completed by its holder " + it);
                rows.remove(it.seq());
            }
        }
        assertEquals(0, q.size(), "drained");
        assertEquals(List.of(), walk(q, pageSize));
    }

    /** The claim rule over the model's rows: not held, no live lease, the oldest remaining row of its key, no live lease on its key. */
    record HeldModel(FakeClock clock, TreeMap<Long, String> rows, Map<Long, Instant> until, TreeMap<Long, Instant> heldAt) {
        boolean live(long seq) {
            Instant u = until.get(seq);
            return u != null && u.isAfter(clock.instant());
        }

        List<Long> eligible(int max) {
            List<Long> out = new ArrayList<>();
            for (Map.Entry<Long, String> r : rows.entrySet()) {
                if (out.size() >= max) break;
                long seq = r.getKey();
                String key = r.getValue();
                if (heldAt.containsKey(seq) || live(seq)) continue;
                if (key != null && rows.headMap(seq).containsValue(key)) continue;
                if (key != null && rows.entrySet().stream().anyMatch(o -> key.equals(o.getValue()) && live(o.getKey()))) continue;
                out.add(seq);
            }
            return out;
        }
    }
}
