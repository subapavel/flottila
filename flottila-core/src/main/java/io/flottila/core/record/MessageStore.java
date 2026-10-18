package io.flottila.core.record;

import io.vertx.core.Future;

import java.util.List;

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

    /** The message's snapshots in step order; empty for an unknown message. */
    Future<List<Snapshot>> timeline(String messageId);

    /** Ids of the messages that carried the key, each once, in recording order. */
    Future<List<String>> findByKey(String name, String value);
}
