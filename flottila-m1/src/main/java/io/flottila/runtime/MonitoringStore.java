package io.flottila.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Operator view store. Postgres for "now", ClickHouse for history — both behind this interface. */
public interface MonitoringStore {
    void append(Snapshot s);
    List<Snapshot> timeline(String messageId);
    Map<String, List<Snapshot>> all();

    final class InMemory implements MonitoringStore {
        private final Map<String, List<Snapshot>> byId = new ConcurrentHashMap<>();
        public void append(Snapshot s) { byId.computeIfAbsent(s.id(), k -> new CopyOnWriteArrayList<>()).add(s); }
        public List<Snapshot> timeline(String id) { return byId.getOrDefault(id, List.of()); }
        public Map<String, List<Snapshot>> all() { return byId; }
    }
}
