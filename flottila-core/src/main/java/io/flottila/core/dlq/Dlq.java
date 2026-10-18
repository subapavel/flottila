package io.flottila.core.dlq;

import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

/**
 * The dead letter queue: state whose loss loses messages, so Postgres in the runtime (CLAUDE.md), in memory for tests.
 * A park completes when the row is durable; a second park of the same message is ignored (the first wins).
 *
 * @since 0.1
 */
public interface Dlq {

    Future<Void> park(Parked parked);

    /** Every parked message, oldest first. */
    Future<List<Parked>> list();

    Future<Optional<Parked>> get(String messageId);

    /** Takes the row out (after a successful retry); unknown ids are a no-op. */
    Future<Void> remove(String messageId);
}
