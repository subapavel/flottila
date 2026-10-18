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
 * @since 0.1
 */
public interface WorkQueue {

    /**
     * One queued message.
     *
     * @since 0.1
     */
    record Item(long seq, String messageId, String flow, int step, String partitionKey) {}

    /** Appends a row; {@code partitionKey} null = no ordering. */
    Future<Void> enqueue(String messageId, String flow, int step, String partitionKey);

    /**
     * Leases up to {@code max} rows to {@code workerId}: a row is claimable when its lease is free,
     * it is the oldest row of its key and that key holds no live lease (or it has no key).
     */
    Future<List<Item>> claim(String workerId, String flow, int max, Duration lease);

    /** Removes a finished row; unknown {@code seq} is a no-op. */
    Future<Void> complete(long seq);

    /**
     * Same-key fast path: completes {@code seq} and, if it had a key, leases the next row of that key
     * to the same worker in the same round-trip — a hot partition never waits for a new claim round.
     */
    Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease);
}
