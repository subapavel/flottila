package dev.rail8.core.record;

import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

/**
 * Where snapshots are saved and read back: a snapshot after every step, searchable by business key. Implemented by
 * {@link InMemoryMessageStore} and by the Postgres store in the runtime. A row is keyed by
 * (message, step, {@link Snapshot#attempt() attempt}): each attempt of a rerun under the same id keeps its own rows, and a
 * second snapshot for the same (message, step, attempt) is ignored — the first row wins, as {@code ON CONFLICT DO NOTHING}
 * does.
 *
 * @since 0.1
 */
public interface MessageStore {

    /** Completes when the snapshot is durable; the message path does not wait for it. */
    Future<Void> record(Snapshot snapshot);

    /**
     * As {@link #record}, but a store whose write backlog is full lets the snapshot wait for room instead of failing it at
     * once. A run's trail uses this, and keeps the run's admission permit while it waits, so a slow store slows intake
     * instead of dropping snapshots. The default is {@link #record}:
     * a store without a bounded backlog has nothing to wait for.
     */
    default Future<Void> recordWaiting(Snapshot snapshot) {
        return record(snapshot);
    }

    /** The message's snapshots by attempt, then step (attempt 1's rows first); empty for an unknown message. */
    Future<List<Snapshot>> timeline(String messageId);

    /**
     * One page of the ids of the messages that carried the key, each once, in the order their first row with the key was
     * inserted. {@code after} is the previous page's {@link Page#next()} cursor, empty for the first page; a cursor stays
     * valid while the store grows, and rows committed behind a cursor appear in a new search. A {@code limit} outside
     * 1..{@link Page#MAX_LIMIT} or a cursor this store did not hand out is an {@link IllegalArgumentException}, thrown or as
     * the failed future's cause.
     */
    Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit);
}
