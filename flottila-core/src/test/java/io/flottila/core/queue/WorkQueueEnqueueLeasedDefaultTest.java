package io.flottila.core.queue;

import io.flottila.core.record.Page;
import io.flottila.testkit.FakeClock;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-16a (D-121 A2): {@code WorkQueue.enqueueLeased}'s default, for a queue that cannot hand off — a plain enqueue and an empty
 * answer, so the intake gives its slot back and the row waits for a claim. Observed through a queue that implements every other
 * method by an in-memory queue and leaves {@code enqueueLeased} to the interface.
 */
class WorkQueueEnqueueLeasedDefaultTest {

    /** Every abstract method by {@code rows}; {@code enqueueLeased} not overridden. */
    record Plain(InMemoryWorkQueue rows) implements WorkQueue {
        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return rows.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return rows.claim(workerId, flow, max, lease); }
        @Override public Future<Boolean> complete(long seq, int attempts) { return rows.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) { return rows.completeAndTakeNext(seq, attempts, workerId, lease); }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return rows.renew(seq, attempts, lease); }
        @Override public Future<Boolean> defer(long seq, int attempts, Duration delay) { return rows.defer(seq, attempts, delay); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return rows.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return rows.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return rows.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return rows.hasItems(flow); }
    }

    @Test
    void aQueueThatCannotHandOffEnqueuesAPlainRowAndLeasesNothing() {
        InMemoryWorkQueue rows = new InMemoryWorkQueue(FakeClock.at(Instant.parse("2026-11-10T10:00:00Z")));
        WorkQueue plain = new Plain(rows);
        assertEquals(Optional.empty(), plain.enqueueLeased("m-1", "orders", 1, null, "w1", Duration.ofSeconds(30)).await(), "nothing leased");
        assertEquals(List.of(new WorkQueue.Item(1, "m-1", "orders", 1, null, 0)), rows.snapshot(), "the row queued plain, never handed out");
        assertEquals(Optional.empty(), rows.leaseHolder(1), "held by nobody");
    }
}
