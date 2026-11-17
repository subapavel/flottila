package dev.rail8.core.record;

import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link MessageStore} in memory, for tests, the bench and a dry run. Losing it loses monitoring data, never a message,
 * so keeping it in memory is allowed; the durable save is the consumer's. Like the Postgres store, a row is keyed by
 * (message, step, attempt) and the first row for a key wins; the timeline lists a message's rows by attempt, then step.
 * The keys of every recorded snapshot count for the key search, one entry per (name, value, message) in the order the
 * messages first carried the key; the search's cursor is the index, in that order, of the page's last id.
 *
 * @since 0.1
 */
public final class InMemoryMessageStore implements MessageStore {

    private record Row(String messageId, int step, int attempt) {}

    private record KeyRow(String name, String value, String messageId) {}

    private record Carried(String name, String value) {}

    private final Map<Row, Snapshot> rows = new LinkedHashMap<>();   // guarded by this
    private final Set<KeyRow> keyRows = new HashSet<>();   // guarded by this
    private final Map<Carried, List<String>> carriers = new HashMap<>();   // guarded by this; per key, the ids in the order they first carried it

    @Override
    public synchronized Future<Void> record(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        rows.putIfAbsent(new Row(snapshot.messageId(), snapshot.step(), snapshot.attempt()), snapshot);
        for (Map.Entry<String, String> k : snapshot.keys().asMap().entrySet()) {
            if (keyRows.add(new KeyRow(k.getKey(), k.getValue(), snapshot.messageId()))) {
                carriers.computeIfAbsent(new Carried(k.getKey(), k.getValue()), c -> new ArrayList<>()).add(snapshot.messageId());
            }
        }
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<List<Snapshot>> timeline(String messageId) {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : rows.values()) if (s.messageId().equals(messageId)) out.add(s);
        out.sort(Comparator.comparingInt(Snapshot::attempt).thenComparingInt(Snapshot::step));
        return Future.succeededFuture(List.copyOf(out));
    }

    @Override
    public synchronized Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
        Page.requireLimit(limit);
        List<String> ids = carriers.getOrDefault(new Carried(name, value), List.of());
        long from = after.map(InMemoryMessageStore::lastIndex).orElse(-1L) + 1;
        int start = (int) Math.min(ids.size(), from);   // a cursor past the end (another key's) is an empty last page
        int end = Math.min(ids.size(), start + limit);
        Optional<String> next = end < ids.size() ? Optional.of(Page.cursor(Integer.toString(end - 1))) : Optional.empty();
        return Future.succeededFuture(new Page<>(ids.subList(start, end), next));
    }

    /** The insertion index a cursor carries: unsigned decimal digits, so a garbled cursor is a NumberFormatException (an IllegalArgumentException). */
    private static long lastIndex(String cursor) {
        return Integer.toUnsignedLong(Integer.parseUnsignedInt(Page.position(cursor)));
    }

    /** Snapshots held. */
    public synchronized int size() { return rows.size(); }
}
