package io.flottila.core.queue;

import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Durable work between a consumer's durable save and the pipeline, for at-least-once flows
 * (ARCHITECTURE §7): one row per message, strictly serial per partition key, parallel across
 * keys, leases with expiry for redelivery. Postgres implementation in runtime (SKIP LOCKED),
 * in-memory one here for tests and the flow builder's unit tests.
 *
 * <p>Every hand-out of a row ({@link #claim}, {@link #completeAndTakeNext}) counts one attempt; the
 * {@link Item#attempts()} it was handed out with is the holder's fencing token (D-055): {@link #renew}
 * and {@link #hold} act only for the current holder. A held row is never handed out again and stays
 * the oldest row of its key, so it blocks the rows behind it until a human deals with it.
 *
 * @since 0.1
 */
public interface WorkQueue {

    /**
     * One queued message, as handed out.
     *
     * @param attempts how many times the row has been handed out, this hand-out included (1 on the first claim)
     * @since 0.1
     */
    record Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts) {}

    /** Appends a row; {@code partitionKey} null = no ordering. */
    Future<Void> enqueue(String messageId, String flow, int step, String partitionKey);

    /**
     * Leases up to {@code max} rows to {@code workerId}: a row is claimable when it is not held, its lease is
     * free, it is the oldest row of its key (held rows included) and that key holds no live lease (or it has no key).
     * Each row handed out counts one more attempt.
     */
    Future<List<Item>> claim(String workerId, String flow, int max, Duration lease);

    /** Removes a finished row, held or not, whoever holds it; unknown {@code seq} is a no-op. */
    Future<Void> complete(long seq);

    /**
     * Same-key fast path: completes {@code seq} and, if it had a key, leases the next row of that key
     * to the same worker in the same round-trip — a hot partition never waits for a new claim round.
     */
    Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease);

    /**
     * Extends the lease of {@code seq} to {@code lease} from now, while the row exists, is not held and was last
     * handed out with {@code attempts} — a lease that ran out and was not claimed again is revived. {@code true} when
     * extended; {@code false} when the caller no longer holds the row (completed, held, or claimed again).
     */
    Future<Boolean> renew(long seq, int attempts, Duration lease);

    /**
     * Marks {@code seq} held, under the same condition as {@link #renew}: it is never claimed again and blocks its key
     * until a human deals with it. {@code true} when held by this call.
     */
    Future<Boolean> hold(long seq, int attempts);
}
