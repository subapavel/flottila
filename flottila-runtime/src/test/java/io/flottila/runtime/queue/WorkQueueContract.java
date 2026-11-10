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
 */
@Timeout(60)
public abstract class WorkQueueContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final String NODE = "n1";

    /** An empty queue for this case. */
    protected abstract WorkQueue emptyQueue();

    /** Every live lease of the queue runs out now (the in-memory clock moves past it; Postgres' locked_until goes into the past). */
    protected abstract void expireLeases();

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
}
