package io.flottila.core.queue;

import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.time.Duration;
import java.time.Instant;
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
 * the oldest row of its key, so it blocks the rows behind it until a human deals with it: {@link #held} lists the held rows
 * and {@link #release} hands one back to the claim (D-067).
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

    /**
     * One held row (D-067).
     *
     * @param item the row as last handed out: its attempts are those of the claim that was held
     * @param heldAt the instant of the hold
     * @since 0.1
     */
    record Held(Item item, Instant heldAt) {}

    /** Appends a row; {@code partitionKey} null = no ordering. */
    Future<Void> enqueue(String messageId, String flow, int step, String partitionKey);

    /**
     * Leases up to {@code max} rows to {@code workerId}: a row is claimable when it is not held, its lease is
     * free, it is the oldest row of its key (held rows included) and that key holds no live lease (or it has no key).
     * Each row handed out counts one more attempt; the rows come in seq order.
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

    /**
     * The held rows of every flow a page at a time, in seq order: up to {@code limit} rows after the cursor {@code after}, and
     * a cursor to the next page when another held row exists. The cursor is a position (a seq), never an offset: a row
     * released or completed meanwhile shifts nothing.
     *
     * @throws IllegalArgumentException {@code limit} is not 1..{@link Page#MAX_LIMIT}, or {@code after} is not a cursor this
     *                                  queue hands out — thrown at the call, not as a failed future
     */
    Future<Page<Held>> held(Optional<String> after, int limit);

    /**
     * Hands a held row back to the claim: clears the hold and the lease, keeps the attempts, so the row is claimable at once
     * and, still the oldest of its key, before the rows behind it. {@code true} only when {@code seq} was held; an unknown,
     * leased or queued row is left as it was.
     */
    Future<Boolean> release(long seq);

    /**
     * {@code true} while any row of {@code flow} exists — due, backing off after a lease ran out, leased or held; a completed row
     * is gone and never counts, nor does another flow's row (D-063: a redeploy that removes an at-least-once flow checks it).
     *
     * @throws NullPointerException {@code flow} is null, thrown at the call, not as a failed future
     */
    Future<Boolean> hasItems(String flow);
}
