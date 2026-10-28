package io.flottila.core.record;

import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

/**
 * Where snapshots go and come back from (ARCHITECTURE §8: a snapshot after every stage, searchable by business key).
 * Two implementations from day one: {@link InMemoryMessageStore} and the Postgres one in the runtime. A second
 * snapshot for the same (message, step) is ignored — the first row wins, as {@code ON CONFLICT DO NOTHING} does.
 *
 * @since 0.1
 */
public interface MessageStore {

    /** Completes when the snapshot is durable; the message path does not wait for it. */
    Future<Void> record(Snapshot snapshot);

    /**
     * As {@link #record}, but a store whose write backlog is full lets the snapshot wait for room instead of failing it at
     * once (D-071) — what a run's trail uses, holding the run's admission permit meanwhile. The default is {@link #record}:
     * a store without a bounded backlog has nothing to wait for.
     */
    default Future<Void> recordWaiting(Snapshot snapshot) {
        return record(snapshot);
    }

    /** The message's snapshots in step order; empty for an unknown message. */
    Future<List<Snapshot>> timeline(String messageId);

    /**
     * One page of the ids of the messages that carried the key, each once, in insertion (seq) order of their first key row
     * (D-057 a). {@code after} is the previous page's {@link Page#next()} cursor, empty for the first page; a cursor stays
     * valid while the store grows, and rows committed behind a cursor appear in a new search. A {@code limit} outside
     * 1..{@link Page#MAX_LIMIT} or a cursor this store did not hand out is an {@link IllegalArgumentException}, thrown or as
     * the failed future's cause.
     */
    Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit);
}
