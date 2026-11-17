package dev.rail8.runtime.queue;

import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16a (D-121 A2): what every {@link WorkQueue} that hands off guarantees for {@code enqueueLeased(id, flow, step, key, workerId,
 * lease)} — the row is written leased to {@code workerId} under {@code lease}, attempts 1, doubts 0, and answered as that hand-out,
 * only when it heads its key: no other row of (flow, key), held or not, leased or not. Otherwise it is a plain row (attempts 0, no
 * holder) and the answer is empty. A row without a key always heads it. Run by {@code InMemoryWorkQueueContractTest} (core's
 * in-memory queue) and {@code PgWorkQueueContractIT} (runtime's Postgres queue, its plain {@code ENQUEUE} with {@code $node, $lease}).
 *
 * <p>The API assumed: {@code Future<Optional<WorkQueue.Item>> WorkQueue.enqueueLeased(String messageId, String flow, int step,
 * String partitionKey, String workerId, Duration lease)}.
 *
 * <p>CORE-14a P3 (D-135 draft): {@code Future<Boolean> WorkQueue.defer(long seq, int attempts, Duration delay)} — under the token
 * (the CURRENT condition of complete, renew and hold), the row is claimable again only {@code delay} after the call on the queue's
 * clock (Postgres: the database's {@code now()}), its holder cleared, its seq, id, attempts, doubts and body kept; {@code false} when
 * the caller no longer holds it, nothing changed. A deferred row still heads its key; its next hand-out counts one more attempt and
 * no doubt.
 *
 * <p>{@code Future<Boolean> WorkQueue.stop(long seq, int attempts, Parked failure)}: the row's current holder parks a keyed message
 * and holds its row, both or neither; {@code stopped(after, limit)} lists such rows. What an operator does with them is
 * {@code FailedMessagesContract}'s.
 */
@Timeout(60)
public abstract class WorkQueueContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final String NODE = "n1";

    /** An empty queue for this case. */
    protected abstract WorkQueue emptyQueue();

    /** Every live lease of the queue runs out now (the in-memory clock moves past it; Postgres' locked_until goes into the past). */
    protected abstract void expireLeases();

    /**
     * {@code d} passes for every row of the queue (CORE-14a): the in-memory clock moves by {@code d}; Postgres' {@code available_at}
     * and {@code locked_until} move {@code d} into the past, so the database clock's {@code now()} meets them as if {@code d} had passed.
     */
    protected abstract void passTime(Duration d);

    /**
     * {@code messageId}'s ingress body is kept on {@link #NODE} — Postgres: its {@code body_refs} row ({@link ParkedRows#keepRef}),
     * without which a park is refused; the in-memory queue keeps no bodies: nothing.
     */
    protected abstract void kept(String messageId);

    /** The DLQ the queue's stop parks in. */
    protected abstract Dlq dlq();

    protected WorkQueue queue;

    @BeforeEach
    void open() {
        queue = emptyQueue();
    }

    protected static <T> T await(io.vertx.core.Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("still pending after 20 s: " + f, e);
        }
    }

    Optional<WorkQueue.Item> leased(String id, String flow, String key) {
        return await(queue.enqueueLeased(id, flow, 1, key, NODE, LEASE));
    }

    @Test
    void enqueueLeasedLeasesOnlyTheHeadOfItsKey() {
        WorkQueue.Item a1 = leased("a1", "t", "K").orElseThrow(() -> new AssertionError("the first row of K heads it: leased"));
        assertEquals("a1", a1.messageId());
        assertEquals(1, a1.attempts(), "written with attempts 1: the hand-out");
        assertEquals(0, a1.doubts(), "a first hand-out is in no doubt");
        assertEquals(new WorkQueue.Item(a1.seq(), "a1", "t", 1, "K", 1), a1, "answered as the row's hand-out");
        assertEquals(List.of(), await(queue.claim("n2", "t", 10, LEASE)), "leased: another worker's claim gets nothing of it");
        assertEquals(List.of(), await(queue.claim(NODE, "t", 10, LEASE)), "nor its holder's own claim while the lease runs");

        assertEquals(Optional.empty(), leased("a2", "t", "K"), "K is busy: a plain row, nothing leased");
        WorkQueue.Completion done = await(queue.completeAndTakeNext(a1.seq(), 1, NODE, LEASE));
        assertTrue(done.completed(), "the hand-out's attempts are its holder's token: " + done);
        WorkQueue.Item a2 = done.next().orElseThrow(() -> new AssertionError("a2 queued behind a1"));
        assertEquals("a2", a2.messageId());
        assertEquals(1, a2.attempts(), "a2 was never handed out before the fast path took it");

        assertTrue(await(queue.hold(a2.seq(), a2.attempts())), "fixture: a2 held");
        assertEquals(Optional.empty(), leased("a3", "t", "K"), "a held row is a row of its key: a3 does not head K");
        assertTrue(leased("g1", "g", "K").isPresent(), "the key is scoped by its flow: K of flow g is free");

        await(queue.enqueue("b1", "t", 1, "B"));
        assertEquals(Optional.empty(), leased("b2", "t", "B"), "a plain unleased row of B heads it: b2 is plain");
        await(queue.enqueue("n0", "t", 1, null));
        WorkQueue.Item n1 = leased("n1", "t", null).orElseThrow(() -> new AssertionError("a row without a key always heads it"));
        assertEquals(1, n1.attempts());

        List<String> claimable = await(queue.claim("n2", "t", 10, LEASE)).stream().map(WorkQueue.Item::messageId).toList();
        assertEquals(List.of("b1", "n0"), claimable, "only the plain rows that head their key are claimable; a3 waits behind held a2, b2 behind b1");
    }

    @Test
    void aHandedOffRowLeftToItsLeaseIsTakenAgainInDoubt() {
        WorkQueue.Item h = leased("h1", "t", "K").orElseThrow();
        WorkQueue.Item plain = leased("u1", "t", null).orElseThrow();
        expireLeases();
        List<WorkQueue.Item> again = await(queue.claim(NODE, "t", 10, LEASE));
        assertEquals(List.of(new WorkQueue.Item(h.seq(), "h1", "t", 1, "K", 2), new WorkQueue.Item(plain.seq(), "u1", "t", 1, null, 2)), again,
                "its lease ran out: claimed again, attempts 2");
        assertEquals(List.of(1, 1), again.stream().map(WorkQueue.Item::doubts).toList(),
                "the hand-out wrote its holder: a re-take counts a doubt, as after a claim (A5)");
    }

    /**
     * CORE-7 C1/C2 (D-122): {@code completeUnkeyed(seq, attempts)} of a row without a partition key completes it only while it is the
     * current holder's — handed out with these attempts (at least 1) and not held — answering done with nothing next and no node;
     * any other call (a stale token, a held row, a row never handed out, a row already gone) answers STALE and changes nothing.
     */
    @Test
    void completeUnkeyedCompletesOnlyTheCurrentHoldersUnheldRow() {
        await(queue.enqueue("u1", "t", 1, null));
        await(queue.enqueue("u2", "t", 1, null));
        await(queue.enqueue("u3", "t", 1, null));
        List<WorkQueue.Item> taken = await(queue.claim(NODE, "t", 3, LEASE));
        assertEquals(List.of("u1", "u2", "u3"), taken.stream().map(WorkQueue.Item::messageId).toList(), "fixture: claimed in seq order");
        await(queue.enqueue("u4", "t", 1, null));   // never handed out: attempts 0
        WorkQueue.Item u1 = taken.get(0);
        WorkQueue.Item u2 = taken.get(1);
        WorkQueue.Item u3 = taken.get(2);
        assertTrue(await(queue.hold(u3.seq(), 1)), "fixture: u3 held");

        assertEquals(new WorkQueue.Completion(true, Optional.empty(), Optional.empty()), await(queue.completeUnkeyed(u1.seq(), 1)),
                "the current holder's row: done, nothing next, no node");
        assertEquals(WorkQueue.Completion.STALE, await(queue.completeUnkeyed(u1.seq(), 1)), "a row already gone is stale");
        assertEquals(WorkQueue.Completion.STALE, await(queue.completeUnkeyed(u2.seq(), 2)), "another token than its hand-out's is stale");
        assertEquals(WorkQueue.Completion.STALE, await(queue.completeUnkeyed(u3.seq(), 1)), "a held row is stale: never deleted");
        long u4 = u3.seq() + 1;
        assertEquals(WorkQueue.Completion.STALE, await(queue.completeUnkeyed(u4, 0)), "a row never handed out matches no token, 0 included");
        assertEquals(new WorkQueue.Completion(true, Optional.empty(), Optional.empty()), await(queue.completeUnkeyed(u2.seq(), 1)),
                "the stale call changed nothing: u2 completes by its own token");

        expireLeases();
        assertEquals(List.of(new WorkQueue.Item(u4, "u4", "t", 1, null, 1)), await(queue.claim(NODE, "t", 10, LEASE)),
                "u1 and u2 are gone, u3 is held; only u4 is claimable, at its first hand-out");
    }

    // --- CORE-14a P3: defer

    static final Duration DELAY = Duration.ofMinutes(10);
    /** Short of {@link #DELAY} by more than a Postgres case's own run time, so its database clock cannot reach the due meanwhile. */
    static final Duration SHORT_OF_DELAY = Duration.ofMinutes(9);

    WorkQueue.Item claimedOne(String workerId, String flow) {
        List<WorkQueue.Item> taken = await(queue.claim(workerId, flow, 1, LEASE));
        assertEquals(1, taken.size(), "fixture: one row claimed: " + taken);
        return taken.get(0);
    }

    @Test
    void deferredNotClaimedBeforeItsTime() {
        await(queue.enqueue("u1", "t", 1, null));
        WorkQueue.Item first = claimedOne(NODE, "t");
        assertTrue(await(queue.defer(first.seq(), first.attempts(), DELAY)), "the current holder defers its row");

        assertEquals(List.of(), await(queue.claim(NODE, "t", 10, LEASE)), "not due: its holder's claim gets nothing");
        assertEquals(List.of(), await(queue.claim("n2", "t", 10, LEASE)), "nor another worker's");
        assertTrue(await(queue.hasItems("t")), "the deferred row is still there");
        passTime(SHORT_OF_DELAY);
        assertEquals(List.of(), await(queue.claim("n2", "t", 10, LEASE)), "still not due " + SHORT_OF_DELAY + " later");
        passTime(DELAY.minus(SHORT_OF_DELAY));
        assertEquals(List.of(new WorkQueue.Item(first.seq(), "u1", "t", 1, null, 2)), await(queue.claim("n2", "t", 10, LEASE)),
                "due: the same row, same seq and id, its next hand-out attempt 2");
    }

    @Test
    void deferredHeadsItsKey() {
        await(queue.enqueue("k1", "t", 1, "K"));
        await(queue.enqueue("k2", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");
        assertEquals("k1", k1.messageId(), "fixture: the head of K");
        assertTrue(await(queue.defer(k1.seq(), k1.attempts(), DELAY)));
        await(queue.enqueue("u1", "t", 1, null));

        List<WorkQueue.Item> unkeyed = await(queue.claim("n2", "t", 10, LEASE));
        assertEquals(List.of("u1"), unkeyed.stream().map(WorkQueue.Item::messageId).toList(),
                "the claim runs (u1), but k2 does not overtake its deferred head");
        assertTrue(await(queue.complete(unkeyed.get(0).seq(), unkeyed.get(0).attempts())), "fixture: u1 done, so its lease cannot run out in the delay");
        passTime(DELAY);
        assertEquals(List.of("k1"), await(queue.claim("n2", "t", 10, LEASE)).stream().map(WorkQueue.Item::messageId).toList(),
                "due: the head of K again, and only it");
    }

    @Test
    void staleDeferMatchesNothing() {
        await(queue.enqueue("u1", "t", 1, null));
        await(queue.enqueue("h1", "t", 1, null));
        await(queue.enqueue("n0", "t", 1, null));
        List<WorkQueue.Item> taken = await(queue.claim(NODE, "t", 2, LEASE));
        WorkQueue.Item u1 = taken.get(0);
        WorkQueue.Item h1 = taken.get(1);
        assertTrue(await(queue.hold(h1.seq(), h1.attempts())), "fixture: h1 held");
        expireLeases();
        WorkQueue.Item again = claimedOne("n2", "t");
        assertEquals(new WorkQueue.Item(u1.seq(), "u1", "t", 1, null, 2), again, "fixture: u1 handed out again to n2");
        long n0 = h1.seq() + 1;

        assertFalse(await(queue.defer(u1.seq(), 1, DELAY)), "the first holder's token is superseded: stale");
        assertFalse(await(queue.defer(u1.seq(), 3, DELAY)), "a token never handed out: stale");
        assertFalse(await(queue.defer(h1.seq(), h1.attempts(), DELAY)), "a held row is never deferred");
        assertFalse(await(queue.defer(n0, 0, DELAY)), "a row never handed out matches no token, 0 included");
        assertFalse(await(queue.defer(n0 + 1_000, 1, DELAY)), "an unknown row");

        assertEquals(List.of(), await(queue.claim(NODE, "t", 10, LEASE)).stream().filter(i -> i.seq() == u1.seq()).toList(),
                "the stale defers changed nothing: u1 stays n2's");
        assertEquals(1, await(queue.held(Optional.empty(), 10)).items().size(), "h1 stays held");
        assertTrue(await(queue.complete(u1.seq(), 2)), "n2 completes u1 by its own token");
    }

    @Test
    void deferredHandOutCountsNoDoubt() {
        await(queue.enqueue("d1", "t", 1, null));
        await(queue.enqueue("l1", "t", 1, null));
        List<WorkQueue.Item> taken = await(queue.claim(NODE, "t", 2, LEASE));
        WorkQueue.Item d1 = taken.get(0);
        assertTrue(await(queue.defer(d1.seq(), d1.attempts(), DELAY)), "d1 deferred; l1 left to its lease");

        passTime(DELAY);
        List<WorkQueue.Item> again = await(queue.claim("n2", "t", 10, LEASE));
        assertEquals(List.of("d1", "l1"), again.stream().map(WorkQueue.Item::messageId).toList(), "both due for n2");
        assertEquals(List.of(2, 2), again.stream().map(WorkQueue.Item::attempts).toList(), "each hand-out counts an attempt");
        assertEquals(List.of(0, 1), again.stream().map(WorkQueue.Item::doubts).toList(),
                "the defer cleared d1's holder: no doubt; l1, left unsettled, counts one (the witness)");

        WorkQueue.Item l1 = again.get(1);
        assertTrue(await(queue.complete(again.get(0).seq(), 2)), "fixture: d1 done by n2");
        assertTrue(await(queue.defer(l1.seq(), l1.attempts(), DELAY)), "l1, in doubt, deferred by n2");
        passTime(DELAY);
        WorkQueue.Item l1Again = claimedOne("n3", "t");
        assertEquals(new WorkQueue.Item(l1.seq(), "l1", "t", 1, null, 3), l1Again, "its next hand-out: attempt 3");
        assertEquals(1, l1Again.doubts(), "its doubt kept, none added");
    }

    // --- a stop: the park of a keyed message holds its key

    /** {@code id}'s failure as its worker parks it after {@code attempt} hand-outs, under {@code key}. */
    static Parked failure(String id, String flow, int attempt, String key) {
        return ParkedRows.byTheWorker(id, flow, attempt, Optional.ofNullable(key));
    }

    static List<String> ids(List<WorkQueue.Item> items) {
        return items.stream().map(WorkQueue.Item::messageId).toList();
    }

    /**
     * {@code stop(seq, attempts, failure)} by the row's holder parks the message and holds its row: the row stays the oldest of its
     * key, so the key's later rows wait while other keys run; it is not claimed, renewed, deferred, completed, released or stopped
     * again, is not among the held rows and is listed as stopped with the rows it blocks and the parked failure; its flow has items.
     */
    @Test
    void aStopParksTheMessageAndHoldsItsKey() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");
        await(queue.enqueue("k2", "t", 1, "K"));
        await(queue.enqueue("k3", "t", 1, "K"));
        await(queue.enqueue("o1", "t", 1, "O"));
        kept("k1");

        assertTrue(await(queue.stop(k1.seq(), k1.attempts(), failure("k1", "t", k1.attempts(), "K"))), "its holder stops k1");
        Parked parked = await(dlq().get("k1")).orElseThrow(() -> new AssertionError("k1 is parked"));
        assertEquals(List.of("t", 1, Optional.of("K")), List.of(parked.flow(), parked.step(), parked.partitionKey()), "as its worker parked it");

        expireLeases();
        assertEquals(List.of("o1"), ids(await(queue.claim("n2", "t", 10, LEASE))), "K waits behind k1, never claimed; key O runs");
        assertFalse(await(queue.renew(k1.seq(), k1.attempts(), LEASE)), "a stopped row is not renewed");
        assertFalse(await(queue.defer(k1.seq(), k1.attempts(), Duration.ofSeconds(1))), "nor deferred");
        assertFalse(await(queue.complete(k1.seq(), k1.attempts())), "nor completed");
        assertFalse(await(queue.stop(k1.seq(), k1.attempts(), failure("k1", "t", k1.attempts(), "K"))), "nor stopped again");
        assertFalse(await(queue.release(k1.seq())), "nor released: only an operator's retry or cancel ends a stop");
        assertEquals(List.of(), await(queue.held(Optional.empty(), 10)).items(), "not among the held rows");
        assertTrue(await(queue.hasItems("t")), "its flow has items");

        Page<WorkQueue.Stopped> page = await(queue.stopped(Optional.empty(), 10));
        assertEquals(1, page.items().size(), "one stopped row: " + page.items());
        WorkQueue.Stopped stopped = page.items().get(0);
        assertEquals(k1, stopped.item(), "k1 as handed out");
        assertEquals(2, stopped.blocks(), "k2 and k3 wait behind it; o1 is another key's");
        assertEquals(Optional.of(parked.error()), stopped.error(), "the parked failure");
        assertEquals(Optional.empty(), page.next(), "the last page");
    }

    /**
     * A stop writes nothing — no park, no hold — and answers false unless the row's current holder stops a keyed row with a failure
     * of that row's message and flow: another token, an unkeyed row, another message's or another flow's failure.
     */
    @Test
    void aStopWritesNothingUnlessItsHolderStopsAKeyedRowWithItsOwnFailure() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");
        await(queue.enqueue("u1", "t", 1, null));
        WorkQueue.Item u1 = claimedOne(NODE, "t");
        kept("k1");
        kept("u1");
        kept("other");

        assertFalse(await(queue.stop(k1.seq(), k1.attempts() + 1, failure("k1", "t", k1.attempts(), "K"))), "another token: stale");
        assertFalse(await(queue.stop(u1.seq(), u1.attempts(), failure("u1", "t", u1.attempts(), null))), "an unkeyed row stops no key");
        assertFalse(await(queue.stop(k1.seq(), k1.attempts(), failure("other", "t", k1.attempts(), "K"))), "another message's failure");
        assertFalse(await(queue.stop(k1.seq(), k1.attempts(), failure("k1", "g", k1.attempts(), "K"))), "another flow's failure");

        for (String id : List.of("k1", "u1", "other")) assertEquals(Optional.empty(), await(dlq().get(id)), "nothing parked: " + id);
        assertEquals(List.of(), await(queue.stopped(Optional.empty(), 10)).items(), "nothing stopped");
        assertTrue(await(queue.complete(k1.seq(), k1.attempts())), "k1 is still its holder's");
        assertTrue(await(queue.complete(u1.seq(), u1.attempts())), "u1 too");
    }

    /** The first park wins: a stop beside a DLQ row of its message keeps that row and still holds the key. */
    @Test
    void aStopKeepsAnEarlierParkOfItsMessage() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");
        kept("k1");
        Parked earlier = ParkedRows.row("k1", "t", 2, dev.rail8.api.Props.empty(), Optional.of("K"));
        await(dlq().park(earlier));

        assertTrue(await(queue.stop(k1.seq(), k1.attempts(), failure("k1", "t", k1.attempts(), "K"))), "stopped");
        assertEquals(Optional.of(2), await(dlq().get("k1")).map(Parked::step), "the earlier park is kept");
        assertEquals(List.of(k1.seq()), await(queue.stopped(Optional.empty(), 10)).items().stream().map(x -> x.item().seq()).toList(),
                "and the key is stopped");
    }

    /** The stopped rows in seq order over every flow, a page at a time; each counts the rows of its own key behind it. */
    @Test
    void stoppedPagesInSeqOrder() {
        List<Long> seqs = new java.util.ArrayList<>();
        for (String[] row : new String[][] {{"a1", "t", "A"}, {"b1", "g", "B"}, {"c1", "t", "C"}}) {
            await(queue.enqueue(row[0], row[1], 1, row[2]));
            WorkQueue.Item item = claimedOne(NODE, row[1]);
            kept(row[0]);
            assertTrue(await(queue.stop(item.seq(), item.attempts(), failure(row[0], row[1], item.attempts(), row[2]))), "fixture: " + row[0] + " stopped");
            seqs.add(item.seq());
        }
        await(queue.enqueue("a2", "t", 1, "A"));
        await(queue.enqueue("a2", "g", 1, "A"));

        Page<WorkQueue.Stopped> first = await(queue.stopped(Optional.empty(), 2));
        assertEquals(seqs.subList(0, 2), first.items().stream().map(x -> x.item().seq()).toList(), "a1 and b1 first");
        assertEquals(List.of(1L, 0L), first.items().stream().map(WorkQueue.Stopped::blocks).toList(), "a1 blocks a2 of flow t, not flow g's");
        Page<WorkQueue.Stopped> second = await(queue.stopped(first.next(), 2));
        assertEquals(seqs.subList(2, 3), second.items().stream().map(x -> x.item().seq()).toList(), "then c1");
        assertEquals(Optional.empty(), second.next(), "the last page");
    }
}
