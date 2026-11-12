package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
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
 * <p>CORE-14b P8 (D-135): {@code Future<Boolean> WorkQueue.restart(String messageId, String flow, String partitionKey, int attempt)} and
 * {@code int WorkQueue.Item.maxAttempts()} (0 = the flow's bound; Postgres: V17 {@code work.max_attempts}, null = 0). The Postgres
 * queue restarts only a message with a DLQ row, deleting it in the same transaction ({@code DlqRestartIT}).
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
     * CORE-14b: {@code messageId} of {@code flow} is parked by its worker after {@code attempt} attempts — Postgres: its DLQ row and its
     * ingress body's kept ref on {@link #NODE} ({@link ParkedRows}), which the Postgres restart deletes and copies; the in-memory queue
     * has no DLQ: nothing.
     */
    protected abstract void parked(String messageId, String flow, int attempt);

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

    // --- CORE-14b P8: restart

    /**
     * {@code restart(id, flow, key, k)} of a message parked after k attempts appends its row at step 1, due at once and unleased, as
     * handed out k times with its own bound k + 1: its next hand-out is attempt k + 1, maxAttempts k + 1, no doubt; a takeover after
     * that hand-out is attempt k + 2, past its bound (the worker parks it unsent). A plain row's maxAttempts is 0: the flow's bound.
     */
    @Test
    void restartedRowRunsOnceThenExhausted() {
        await(queue.enqueue("p1", "t", 1, null));
        parked("r1", "t", 3);
        assertTrue(await(queue.restart("r1", "t", null, 3)), "the parked message is restarted");

        List<WorkQueue.Item> taken = await(queue.claim(NODE, "t", 10, LEASE));
        assertEquals(List.of("p1", "r1"), taken.stream().map(WorkQueue.Item::messageId).toList(), "both due at once, in seq order");
        WorkQueue.Item plain = taken.get(0);
        WorkQueue.Item once = taken.get(1);
        assertEquals(List.of(1, 0), List.of(plain.attempts(), plain.maxAttempts()), "a plain row: no bound of its own (0 = the flow's)");
        assertEquals(1, once.step(), "restarted at step 1");
        assertEquals(List.of(4, 4, 0), List.of(once.attempts(), once.maxAttempts(), once.doubts()),
                "handed out once more as attempt k + 1 = 4 of its bound 4, in no doubt");
        assertTrue(await(queue.complete(plain.seq(), 1)), "fixture: p1 done");

        expireLeases();
        WorkQueue.Item again = claimedOne("n2", "t");
        assertEquals(once.seq(), again.seq(), "the same row");
        assertEquals(List.of(5, 4, 1), List.of(again.attempts(), again.maxAttempts(), again.doubts()),
                "its takeover: attempt 5, past its bound 4 — exhausted —, in doubt");
    }

    /**
     * O7: a keyed restart enqueues under the kept key at the tail of that key — a new seq behind the key's rows queued meanwhile —
     * and is handed out only once they are done, still keyed, as attempt k + 1 of its bound k + 1.
     */
    @Test
    void aKeyedRestartJoinsTheTailOfItsKey() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");
        await(queue.enqueue("k2", "t", 1, "K"));
        parked("r1", "t", 1);
        assertTrue(await(queue.restart("r1", "t", "K", 1)), "the parked message is restarted under its key");

        assertEquals(List.of(), await(queue.claim("n2", "t", 10, LEASE)), "K is busy with k1: nothing of K, the restart included");
        assertTrue(await(queue.complete(k1.seq(), k1.attempts())), "fixture: k1 done");
        WorkQueue.Item k2 = claimedOne("n2", "t");
        assertEquals("k2", k2.messageId(), "k2, queued before the restart, goes first: the restart joined the tail of K");
        assertEquals(List.of(), await(queue.claim("n2", "t", 10, LEASE)), "the restart waits behind k2");
        assertTrue(await(queue.complete(k2.seq(), k2.attempts())), "fixture: k2 done");

        WorkQueue.Item r1 = claimedOne("n2", "t");
        assertEquals("r1", r1.messageId());
        assertTrue(r1.seq() > k2.seq(), "a new seq behind k2: " + r1.seq() + " vs " + k2.seq());
        assertEquals("K", r1.partitionKey(), "it keeps its key");
        assertEquals(List.of(2, 2), List.of(r1.attempts(), r1.maxAttempts()), "attempt k + 1 = 2 of its bound 2");
    }
}
