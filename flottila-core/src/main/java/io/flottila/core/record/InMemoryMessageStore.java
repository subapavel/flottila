package io.flottila.core.record;

import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The MessageStore in memory: tests, the bench, a dry run. Loss loses monitoring, never a message, so memory is allowed
 * (CLAUDE.md state rule); the durable save is the consumer's.
 *
 * @since 0.1
 */
public final class InMemoryMessageStore implements MessageStore {

    private record Row(String messageId, int step) {}

    private final Map<Row, Snapshot> rows = new LinkedHashMap<>();   // guarded by this

    @Override
    public synchronized Future<Void> record(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        rows.putIfAbsent(new Row(snapshot.messageId(), snapshot.step()), snapshot);
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<List<Snapshot>> timeline(String messageId) {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : rows.values()) if (s.messageId().equals(messageId)) out.add(s);
        out.sort(Comparator.comparingInt(Snapshot::step));
        return Future.succeededFuture(List.copyOf(out));
    }

    @Override
    public synchronized Future<List<String>> findByKey(String name, String value) {
        List<String> ids = new ArrayList<>();
        for (Snapshot s : rows.values()) {
            if (value.equals(s.keys().get(name)) && !ids.contains(s.messageId())) ids.add(s.messageId());
        }
        return Future.succeededFuture(List.copyOf(ids));
    }

    /** Snapshots held. */
    public synchronized int size() { return rows.size(); }
}
