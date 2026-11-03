package io.flottila.sim;

import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The message store as the flows see it (D-101): the in-memory one, its timelines kept per message as it keeps them — the
 * first snapshot of each step, in step order — so a read does not scan every row of the run. A timeline read is a worker
 * run's first simulation call: its thread binds to the run the queue handed out. The oracle reads {@link #recorded}.
 */
final class SimStore implements MessageStore {

    private final MessageStore store;
    private final Runs runs;
    private final Map<String, TreeMap<Integer, Snapshot>> timelines = new HashMap<>();   // guarded by this

    SimStore(MessageStore store, Runs runs) {
        this.store = store;
        this.runs = runs;
    }

    @Override public Future<Void> record(Snapshot snapshot) {
        synchronized (this) {
            timelines.computeIfAbsent(snapshot.messageId(), id -> new TreeMap<>()).putIfAbsent(snapshot.step(), snapshot);
        }
        return store.record(snapshot);
    }

    @Override public Future<List<Snapshot>> timeline(String messageId) {
        runs.bind(messageId);
        return Future.succeededFuture(recorded(messageId));
    }

    @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
        return store.findByKey(name, value, after, limit);
    }

    /** The message's snapshots in step order, as recorded; empty for an unknown message. */
    synchronized List<Snapshot> recorded(String messageId) {
        return List.copyOf(timelines.getOrDefault(messageId, new TreeMap<>()).values());
    }
}
