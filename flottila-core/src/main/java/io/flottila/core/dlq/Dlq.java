package io.flottila.core.dlq;

import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.util.Optional;

/**
 * The dead letter queue: state whose loss loses messages, so Postgres in the runtime (CLAUDE.md), in memory for tests.
 * A park completes when the row is durable; a second park of the same message is ignored (the first wins).
 *
 * @since 0.1
 */
public interface Dlq {

    Future<Void> park(Parked parked);

    /**
     * One page of the parked messages, oldest first ({@code parkedAt}, then id; D-057 a). {@code after} is the previous
     * page's {@link Page#next()} cursor, empty for the first page; the cursor is a position, so a removal before it skips
     * nothing. A {@code limit} outside 1..{@link Page#MAX_LIMIT} or a cursor this store did not hand out is an
     * {@link IllegalArgumentException}, thrown or as the failed future's cause.
     */
    Future<Page<Parked>> list(Optional<String> after, int limit);

    Future<Optional<Parked>> get(String messageId);

    /** Takes the row out (after a successful retry); unknown ids are a no-op. */
    Future<Void> remove(String messageId);
}
