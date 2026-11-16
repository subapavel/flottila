package dev.rail8.core.queue;

import dev.rail8.testkit.FakeClock;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14b P8 (implementer's cases): the in-memory restart refuses a null message id or flow at the call, appending nothing — a
 * row without either could never be claimed or completed —, as its enqueue does; the rest of its contract is
 * {@code WorkQueueContract} (runtime).
 *
 * <p>FIX-275 (F-57, D-162 (5)): the refusal beside a row of the id in the flow, its scope (id and flow) and which row it names, in
 * core's own tree, so that core's own coverage and mutation runs see them.
 */
class InMemoryWorkQueueRestartTest {

    final InMemoryWorkQueue queue = new InMemoryWorkQueue(FakeClock.at(Instant.EPOCH));

    @Test
    void aNullIdOrFlowIsRefusedAndAppendsNothing() {
        assertEquals("messageId", assertThrows(NullPointerException.class, () -> queue.restart(null, "t", null, 1)).getMessage());
        assertEquals("flow", assertThrows(NullPointerException.class, () -> queue.restart("m-1", null, null, 1)).getMessage());
        assertEquals(0, queue.size(), "nothing appended");
        assertEquals(List.of(), queue.claim("n1", "t", 10, Duration.ofSeconds(30)).await());
    }

    /** A restart takes a seq of its own: rows enqueued after it neither replace it nor head before it (PIT r1: seq counted down). */
    @Test
    void rowsEnqueuedAfterARestartQueueBehindIt() {
        queue.restart("m-1", "t", null, 2).await();
        queue.enqueue("m-2", "t", 1, null).await();
        queue.enqueue("m-3", "t", 1, null).await();
        List<WorkQueue.Item> claimed = queue.claim("n1", "t", 10, Duration.ofSeconds(30)).await();
        assertEquals(List.of("m-1", "m-2", "m-3"), claimed.stream().map(WorkQueue.Item::messageId).toList(), "in the order they were added");
        assertEquals(3, claimed.stream().mapToLong(WorkQueue.Item::seq).distinct().count(), "three seqs");
    }

    // --- FIX-275 (F-57, D-162 (5)): a restart beside a row of its id, in core's own tree (WorkQueueContract runs from runtime only)

    static final Duration LEASE = Duration.ofSeconds(30);
    static final String ID = "order-a";

    /** {@code restart}'s refusal: its future fails {@link UnsettledException}. */
    static UnsettledException refusedAsUnsettled(Future<Boolean> restart, String what) {
        Throwable failed = assertThrows(Throwable.class, () -> restart.await(5, TimeUnit.SECONDS), what + ": refused, not restarted");
        return assertInstanceOf(UnsettledException.class, failed, what + ": refused as unsettled: " + failed);
    }

    /** {@code id}'s row of {@code flow}, never keyed, leased to {@code n1} as it heads its key: the hand-out a park leaves behind. */
    WorkQueue.Item leasedRow(String id, String flow) {
        return queue.enqueueLeased(id, flow, 1, null, "n1", LEASE).await().orElseThrow(() -> new AssertionError("fixture: an unkeyed row heads it"));
    }

    static List<String> ids(List<WorkQueue.Item> items) {
        return items.stream().map(WorkQueue.Item::messageId).toList();
    }

    /**
     * FIX-275: a restart while a row of its id is in its flow — W1, still leased: a park whose settle has not landed — fails
     * {@link UnsettledException} naming the message and W1's seq, in a message that names both and says it is not settled yet, and
     * appends nothing: the queue keeps its two rows, and a claim hands out the other id's row and nothing of the id.
     */
    @Test
    void aRestartBesideARowOfItsIdInItsFlowIsRefusedAsUnsettledAndAppendsNothing() {
        queue.enqueue("other", "t", 1, null).await();
        WorkQueue.Item w1 = leasedRow(ID, "t");

        UnsettledException refused = refusedAsUnsettled(queue.restart(ID, "t", null, 3), "a restart beside W1");
        assertEquals(ID, refused.messageId(), "it names the message");
        assertEquals(w1.seq(), refused.seq(), "it names W1");
        String text = refused.getMessage();
        assertTrue(text.contains(ID), "the message names the id: " + text);
        assertTrue(Pattern.compile("\\b" + w1.seq() + "\\b").matcher(text).find(), "the message names W1's seq " + w1.seq() + ": " + text);
        assertTrue(text.contains("not settled yet"), "and says it is not settled yet: " + text);

        assertEquals(2, queue.size(), "nothing appended: the other row and W1");
        assertEquals(List.of("other"), ids(queue.claim("n2", "t", 10, LEASE).await()),
                "a claim hands out the other id's row and nothing of the id: W1 is leased, and no restarted row (due, unleased) is there");
    }

    /** FIX-275: the probe is scoped by the flow — a row of the id in another flow refuses nothing; the restart's row is claimable. */
    @Test
    void aRowOfItsIdInAnotherFlowRefusesNothing() {
        queue.enqueue(ID, "u", 1, null).await();

        assertTrue(queue.restart(ID, "t", null, 1).await(), "no row of the id in flow t (one in flow u): restarted");
        assertEquals(2, queue.size(), "the restart's row beside flow u's");
        List<WorkQueue.Item> claimed = queue.claim("n2", "t", 10, LEASE).await();
        assertEquals(List.of(ID), ids(claimed), "the restart's row is claimable in flow t");
        WorkQueue.Item r = claimed.get(0);
        assertEquals(List.of(1, 2, 2), List.of(r.step(), r.attempts(), r.maxAttempts()), "at step 1, attempt k + 1 = 2 of its bound 2");
        assertEquals(List.of(ID), ids(queue.claim("n2", "u", 10, LEASE).await()), "flow u's row is still there, untouched");
    }

    /** FIX-275: the probe is scoped by the id — a row of another id in the flow refuses nothing; the restart's row is claimable. */
    @Test
    void aRowOfAnotherIdInItsFlowRefusesNothing() {
        queue.enqueue("order-b", "t", 1, null).await();

        assertTrue(queue.restart(ID, "t", null, 1).await(), "no row of order-a in flow t (one of order-b): restarted");
        assertEquals(2, queue.size(), "the restart's row beside order-b's");
        List<WorkQueue.Item> claimed = queue.claim("n2", "t", 10, LEASE).await();
        assertEquals(List.of("order-b", ID), ids(claimed), "both claimable, the restart's behind order-b's");
        WorkQueue.Item r = claimed.get(1);
        assertEquals(List.of(1, 2, 2), List.of(r.step(), r.attempts(), r.maxAttempts()), "at step 1, attempt k + 1 = 2 of its bound 2");
    }

    /**
     * FIX-275 (M9): beside two rows of the id in its flow — W1 enqueued, then W2 — the refusal names the older, W1; once W1 settled,
     * the one left, W2. Neither refusal appends anything.
     */
    @Test
    void besideTwoRowsOfItsIdInItsFlowTheRefusalNamesTheOlder() {
        WorkQueue.Item w1 = leasedRow(ID, "t");
        WorkQueue.Item w2 = leasedRow(ID, "t");

        assertEquals(w1.seq(), refusedAsUnsettled(queue.restart(ID, "t", null, 1), "beside W1 and W2").seq(),
                "it names the older, W1 (" + w1.seq() + "), not W2 (" + w2.seq() + ")");
        assertTrue(queue.complete(w1.seq(), w1.attempts()).await(), "fixture: W1 settles");
        assertEquals(w2.seq(), refusedAsUnsettled(queue.restart(ID, "t", null, 1), "beside W2").seq(), "then the one left, W2");
        assertEquals(1, queue.size(), "neither refusal appended: W2 alone");
    }
}
