package dev.rail8.runtime.queue;

import dev.rail8.api.Props;
import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.FailedMessages.Done;
import dev.rail8.core.dlq.FailedMessages.Event;
import dev.rail8.core.dlq.FailedMessages.Kind;
import dev.rail8.core.dlq.FailedMessages.NotParked;
import dev.rail8.core.dlq.FailedMessages.Unsettled;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.WorkQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link FailedMessages} guarantees over its {@link WorkQueue} and DLQ. A message of an ordered flow that failed for
 * good is stopped: parked, its row held as the oldest of its key. A retry hands that row back in place, so it runs before the rows
 * behind it; a cancel deletes it, so the key's next row runs: either way the key keeps its order. A parked message without a work
 * row (an unordered one) is retried at the tail of its key and cancelled by its DLQ row alone. An act beside a work row of the
 * message that is not stopped is refused as unsettled, one without a matching DLQ row as not parked; neither writes anything.
 * Run by {@code InMemoryFailedMessagesContractTest} and {@code PgFailedMessagesContractIT}.
 */
@Timeout(60)
public abstract class FailedMessagesContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final String NODE = "n1";
    protected static final FailedMessages.Actor BY = new FailedMessages.Actor("ops", "127.0.0.1");

    /** An empty queue and DLQ for this case; {@link #failed()} acts on them. */
    protected abstract WorkQueue emptyQueue();

    /** The operator's acts on the queue {@link #emptyQueue} returned and its DLQ. */
    protected abstract FailedMessages failed();

    /** The DLQ the queue parks in. */
    protected abstract Dlq dlq();

    /** {@code messageId}'s ingress body is kept on {@link #NODE} (Postgres: its {@code body_refs} row); the in-memory one: nothing. */
    protected abstract void kept(String messageId);

    /** Every live lease of the queue runs out now. */
    protected abstract void expireLeases();

    protected WorkQueue queue;

    @BeforeEach
    void open() {
        queue = emptyQueue();
    }

    protected static <T> T await(io.vertx.core.Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("still pending after 20 s: " + f, e);
        }
    }

    /** {@code id} of flow t under {@code key}, handed out to {@link #NODE} as the head of its key and stopped by it; its hand-out. */
    WorkQueue.Item stopped(String id, String key) {
        WorkQueue.Item item = await(queue.enqueueLeased(id, "t", 1, key, NODE, LEASE)).orElseThrow(() -> new AssertionError("fixture: " + id + " heads " + key));
        kept(id);
        assertTrue(await(queue.stop(item.seq(), item.attempts(), ParkedRows.byTheWorker(id, "t", item.attempts(), Optional.of(key)))),
                "fixture: " + id + " stopped");
        return item;
    }

    /** {@code id} of flow t parked by its worker after {@code attempt} hand-outs under {@code key} (null: none), with no work row. */
    void parkedOnly(String id, int attempt, String key) {
        kept(id);
        await(dlq().park(ParkedRows.byTheWorker(id, "t", attempt, Optional.ofNullable(key))));
    }

    WorkQueue.Item claimedOne(String workerId) {
        List<WorkQueue.Item> taken = await(queue.claim(workerId, "t", 1, LEASE));
        assertEquals(1, taken.size(), "one row claimed: " + taken);
        return taken.get(0);
    }

    List<String> claimable() {
        return await(queue.claim("n9", "t", 10, LEASE)).stream().map(WorkQueue.Item::messageId).toList();
    }

    static Done done(FailedMessages.Act act) {
        return assertInstanceOf(Done.class, act, "the act committed");
    }

    static List<Object> facts(Event e) {
        return List.of(e.messageId(), e.flow(), e.actor(), e.kind(), e.attempt(), e.reason(), e.error());
    }

    /**
     * A retry of a stopped message hands its row back in place: the same seq, due at once and unleased, attempt k + 1 of its own
     * bound k + 1 in no doubt. It runs before the key's rows behind it, which follow in their order once it is done.
     */
    @Test
    void aRetryRunsAStoppedMessageBeforeTheRowsBehindIt() {
        WorkQueue.Item k1 = stopped("k1", "K");
        await(queue.enqueue("k2", "t", 1, "K"));
        await(queue.enqueue("k3", "t", 1, "K"));

        Event retried = done(await(failed().retry("k1", "t", "K", 1, BY))).event();
        assertEquals(List.of("k1", "t", "ops", Kind.RETRY, OptionalInt.of(2), Optional.empty(), Optional.empty()), facts(retried), "one retry event");
        assertEquals(Optional.empty(), await(dlq().get("k1")), "its DLQ row is gone");
        assertEquals(List.of(), await(queue.stopped(Optional.empty(), 10)).items(), "the key is no longer stopped");

        WorkQueue.Item again = claimedOne("n2");
        assertEquals(List.of(k1.seq(), 2, 2, 0), List.of(again.seq(), again.attempts(), again.maxAttempts(), again.doubts()),
                "k1 first, in place: its seq, attempt 2 of its bound 2, no doubt");
        assertEquals(List.of(), claimable(), "k2 and k3 wait behind it");
        WorkQueue.Completion next = await(queue.completeAndTakeNext(again.seq(), again.attempts(), "n2", LEASE));
        assertEquals(Optional.of("k2"), next.next().map(WorkQueue.Item::messageId), "then k2");
        WorkQueue.Completion last = await(queue.completeAndTakeNext(next.next().orElseThrow().seq(), 1, "n2", LEASE));
        assertEquals(Optional.of("k3"), last.next().map(WorkQueue.Item::messageId), "then k3");
    }

    /** A retried message that fails again stops its key again, and is retried again as one more attempt. */
    @Test
    void aRetriedMessageThatFailsAgainStopsAgain() {
        stopped("k1", "K");
        await(queue.enqueue("k2", "t", 1, "K"));
        done(await(failed().retry("k1", "t", "K", 1, BY)));
        WorkQueue.Item second = claimedOne("n2");

        assertTrue(await(queue.stop(second.seq(), second.attempts(), ParkedRows.byTheWorker("k1", "t", 2, Optional.of("K")))), "stopped again");
        assertEquals(List.of(), claimable(), "k2 still waits");
        assertSame(NotParked.INSTANCE, await(failed().retry("k1", "t", "K", 1, BY)), "the first park is gone: its retry finds nothing");
        assertEquals(OptionalInt.of(3), done(await(failed().retry("k1", "t", "K", 2, BY))).event().attempt(), "the second park's retry: attempt 3");
        WorkQueue.Item third = claimedOne("n2");
        assertEquals(List.of(second.seq(), 3, 3), List.of(third.seq(), third.attempts(), third.maxAttempts()), "the same row, attempt 3 of 3");
    }

    /**
     * A cancel of a stopped message deletes its row and its DLQ row and records its reason, stripped, and its failure: the key's
     * next rows run in their order, and the cancelled message never runs.
     */
    @Test
    void aCancelEndsAStoppedMessageAndTheRowsBehindItRunInOrder() {
        stopped("k1", "K");
        await(queue.enqueue("k2", "t", 1, "K"));
        await(queue.enqueue("k3", "t", 1, "K"));

        Event cancelled = done(await(failed().cancel("k1", BY, Optional.of("  sent by hand  ")))).event();
        assertEquals(List.of("k1", "t", "ops", Kind.CANCEL, OptionalInt.empty(), Optional.of("sent by hand"), Optional.of("partner down (test)")),
                facts(cancelled), "one cancel event");
        assertEquals(Optional.empty(), await(dlq().get("k1")), "its DLQ row is gone");
        assertEquals(List.of(), await(queue.stopped(Optional.empty(), 10)).items(), "the key is no longer stopped");

        WorkQueue.Item k2 = claimedOne("n2");
        assertEquals("k2", k2.messageId(), "the key's next row runs");
        WorkQueue.Completion next = await(queue.completeAndTakeNext(k2.seq(), k2.attempts(), "n2", LEASE));
        assertEquals(Optional.of("k3"), next.next().map(WorkQueue.Item::messageId), "then k3");
        assertTrue(await(queue.complete(next.next().orElseThrow().seq(), 1)), "fixture: k3 done");
        assertFalse(await(queue.hasItems("t")), "nothing of k1 is left to run");
        assertSame(NotParked.INSTANCE, await(failed().cancel("k1", BY, Optional.empty())), "a second cancel finds nothing");
    }

    /** A cancel takes no reason, or one of 1 to 1024 characters after stripping; any other is refused at the call and changes nothing. */
    @Test
    void aCancelReasonIsCheckedAtTheCall() {
        stopped("k1", "K");
        for (String bad : List.of("   ", "x".repeat(FailedMessages.REASON_MAX + 1))) {
            assertThrows(IllegalArgumentException.class, () -> failed().cancel("k1", BY, Optional.of(bad)), "refused: " + bad.length() + " chars");
        }
        assertTrue(await(dlq().get("k1")).isPresent(), "nothing changed: k1 is parked");
        assertEquals(1, await(queue.stopped(Optional.empty(), 10)).items().size(), "and stopped");
        String longest = " " + "x".repeat(FailedMessages.REASON_MAX) + " ";
        assertEquals(Optional.of("x".repeat(FailedMessages.REASON_MAX)), done(await(failed().cancel("k1", BY, Optional.of(longest)))).event().reason(),
                "1024 characters after stripping are taken");
    }

    /** A cancel's event keeps the failure's first 4096 characters. */
    @Test
    void aCancelKeepsTheFailureCutTo4096Characters() {
        kept("u1");
        Parked p = ParkedRows.byTheWorker("u1", "t", 1);
        String error = "e".repeat(FailedMessages.ERROR_MAX + 10);
        await(dlq().park(new Parked(p.messageId(), p.flow(), p.step(), p.stepName(), error, p.parkedAt(), p.headers(), p.props(), p.keys(),
                p.bodyHash(), p.bodySize(), p.bodyKey(), p.versions(), p.partitionKey())));

        assertEquals(Optional.of(error.substring(0, FailedMessages.ERROR_MAX)), done(await(failed().cancel("u1", BY, Optional.empty()))).event().error());
    }

    /** A cancel of a message parked without a work row — an unordered one — deletes its DLQ row alone. */
    @Test
    void aCancelOfAnUnorderedParkDeletesItsDlqRowAlone() {
        await(queue.enqueue("x1", "t", 1, null));
        parkedOnly("u1", 1, null);

        assertEquals(Optional.empty(), done(await(failed().cancel("u1", BY, Optional.empty()))).event().reason(), "cancelled without a reason");
        assertEquals(Optional.empty(), await(dlq().get("u1")), "its DLQ row is gone");
        assertEquals(List.of("x1"), claimable(), "the queue's rows are untouched");
    }

    /**
     * A retry of a message parked without a work row appends one at step 1, due at once and unleased, as handed out k times with its
     * own bound k + 1: attempt k + 1 in no doubt; a takeover after that is attempt k + 2, past its bound, in doubt.
     */
    @Test
    void aRetryWithoutAWorkRowAppendsOneThatRunsOnce() {
        await(queue.enqueue("p1", "t", 1, null));
        parkedOnly("r1", 3, null);

        assertEquals(OptionalInt.of(4), done(await(failed().retry("r1", "t", null, 3, BY))).event().attempt());
        assertEquals(Optional.empty(), await(dlq().get("r1")), "its DLQ row is gone");
        List<WorkQueue.Item> taken = await(queue.claim(NODE, "t", 10, LEASE));
        assertEquals(List.of("p1", "r1"), taken.stream().map(WorkQueue.Item::messageId).toList(), "both due at once, in seq order");
        WorkQueue.Item once = taken.get(1);
        assertEquals(List.of(1, 4, 4, 0), List.of(once.step(), once.attempts(), once.maxAttempts(), once.doubts()), "step 1, attempt 4 of its bound 4");
        assertTrue(await(queue.complete(taken.get(0).seq(), 1)), "fixture: p1 done");

        expireLeases();
        WorkQueue.Item again = claimedOne("n2");
        assertEquals(List.of(once.seq(), 5, 4, 1), List.of(again.seq(), again.attempts(), again.maxAttempts(), again.doubts()),
                "its takeover: attempt 5, past its bound 4, in doubt");
    }

    /** A keyed message parked without a work row — before its key could stop — is retried at the tail of its key, keeping its key. */
    @Test
    void aKeyedRetryWithoutAWorkRowJoinsTheTailOfItsKey() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE);
        await(queue.enqueue("k2", "t", 1, "K"));
        parkedOnly("r1", 1, "K");

        done(await(failed().retry("r1", "t", "K", 1, BY)));
        assertEquals(List.of(), claimable(), "K is busy with k1");
        assertTrue(await(queue.complete(k1.seq(), k1.attempts())), "fixture: k1 done");
        WorkQueue.Item k2 = claimedOne("n2");
        assertEquals("k2", k2.messageId(), "k2, queued before the retry, goes first");
        assertTrue(await(queue.complete(k2.seq(), k2.attempts())), "fixture: k2 done");
        WorkQueue.Item r1 = claimedOne("n2");
        assertEquals(List.of("r1", "K", 2, 2), List.of(r1.messageId(), r1.partitionKey(), r1.attempts(), r1.maxAttempts()), "then r1 under K");
        assertTrue(r1.seq() > k2.seq(), "a new seq behind k2");
    }

    /**
     * A retry finds only the park its worker wrote at step 1 after that attempt, in that flow; anything else is not parked, and
     * nothing changes.
     */
    @Test
    void aRetryOfAnotherParkIsNotParked() {
        assertSame(NotParked.INSTANCE, await(failed().retry("r1", "t", null, 1, BY)), "no DLQ row");
        parkedOnly("r1", 1, null);
        kept("s2");
        await(dlq().park(ParkedRows.row("s2", "t", 2, Props.empty().with(dev.rail8.api.Envelope.ATTEMPT, 1))));

        assertSame(NotParked.INSTANCE, await(failed().retry("r1", "g", null, 1, BY)), "another flow");
        assertSame(NotParked.INSTANCE, await(failed().retry("r1", "t", null, 2, BY)), "another attempt");
        assertSame(NotParked.INSTANCE, await(failed().retry("s2", "t", null, 1, BY)), "parked at step 2");
        assertTrue(await(dlq().get("r1")).isPresent(), "nothing changed: r1 is parked");
        assertTrue(await(dlq().get("s2")).isPresent(), "and s2");
        assertFalse(await(queue.hasItems("t")), "nothing queued");
        assertSame(NotParked.INSTANCE, await(failed().cancel("none", BY, Optional.empty())), "a cancel without a DLQ row");
    }

    /**
     * Beside a work row of the message in its flow that is not stopped — its park has not settled — a retry and a cancel are refused
     * as unsettled naming the oldest such row, keyed or not, and write nothing; once it settled the retry runs. A row of the
     * message in another flow refuses nothing.
     */
    @Test
    void anActBesideAWorkRowThatIsNotStoppedIsUnsettled() {
        await(queue.enqueue("r1", "t", 1, "K"));
        WorkQueue.Item w1 = claimedOne(NODE);
        await(queue.enqueue("r1", "t", 1, null));
        WorkQueue.Item w2 = claimedOne(NODE);
        await(queue.enqueue("r1", "g", 1, null));
        parkedOnly("r1", 1, null);

        assertEquals(new Unsettled(w1.seq()), await(failed().retry("r1", "t", null, 1, BY)), "a retry names the older row, W1");
        assertEquals(new Unsettled(w1.seq()), await(failed().retry("r1", "t", "K", 1, BY)), "under W1's key too");
        assertEquals(new Unsettled(w1.seq()), await(failed().cancel("r1", BY, Optional.empty())), "and a cancel");
        assertTrue(await(queue.complete(w1.seq(), w1.attempts())), "fixture: W1 settles");
        assertEquals(new Unsettled(w2.seq()), await(failed().retry("r1", "t", null, 1, BY)), "then W2");
        assertTrue(await(dlq().get("r1")).isPresent(), "nothing written: r1 is parked");
        assertTrue(await(queue.complete(w2.seq(), w2.attempts())), "fixture: W2 settles");

        done(await(failed().retry("r1", "t", null, 1, BY)));
        WorkQueue.Item retried = claimedOne(NODE);
        assertNull(retried.partitionKey(), "one row of r1 in flow t, the retry's");
        assertEquals(List.of(), claimable(), "and no other");
    }
}
