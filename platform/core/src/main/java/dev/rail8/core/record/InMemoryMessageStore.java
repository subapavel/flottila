package dev.rail8.core.record;

import io.vertx.core.Future;

import java.time.DateTimeException;
import java.time.Instant;
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
 * messages first carried the key; the search's cursor is the index, in that order, of the page's last id. The search by step
 * reads every row it holds: per message, the row of the step in the latest attempt that has one, newest first by its start
 * and then by id; its cursor is the page's last start and id.
 *
 * @since 0.1
 */
public final class InMemoryMessageStore implements MessageStore {

    private record Row(String messageId, int step, int attempt) {}

    private record KeyRow(String name, String value, String messageId) {}

    private record Carried(String name, String value) {}

    /** Where a message stands in a search by step: the start of its row of the step, then its id. */
    private record Passed(Instant startedAt, String messageId) {}

    private static final Comparator<Passed> NEWEST_FIRST = Comparator.comparing(Passed::startedAt).thenComparing(Passed::messageId).reversed();

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

    @Override
    public synchronized Future<Page<String>> findByStep(String flow, String step, Optional<String> after, int limit) {
        Page.requireLimit(limit);
        Optional<Passed> from = after.map(InMemoryMessageStore::passed);
        Map<String, Snapshot> latest = new HashMap<>();
        for (Snapshot s : rows.values()) {
            if (s.flow().equals(flow) && s.stepName().equals(step)) latest.merge(s.messageId(), s, (a, b) -> b.attempt() > a.attempt() ? b : a);
        }
        List<Passed> passed = latest.values().stream().map(s -> new Passed(s.startedAt(), s.messageId())).sorted(NEWEST_FIRST)
                .filter(p -> from.isEmpty() || NEWEST_FIRST.compare(p, from.get()) > 0).toList();
        List<String> ids = passed.stream().limit(limit).map(Passed::messageId).toList();
        Optional<String> next = passed.size() > limit ? Optional.of(cursor(passed.get(limit - 1))) : Optional.empty();
        return Future.succeededFuture(new Page<>(ids, next));
    }

    /** {@code <epoch second>:<nano>:<id>} of {@code p}, as a cursor. */
    private static String cursor(Passed p) {
        return Page.cursor(p.startedAt().getEpochSecond() + ":" + p.startedAt().getNano() + ":" + p.messageId());
    }

    /** The position a cursor of the search by step carries; any other text is an IllegalArgumentException. */
    private static Passed passed(String cursor) {
        String[] parts = Page.position(cursor).split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("not a cursor of a search by step");
        try {
            return new Passed(Instant.ofEpochSecond(Long.parseLong(parts[0]), Integer.parseUnsignedInt(parts[1])), parts[2]);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("not a cursor of a search by step", e);
        }
    }

    /** The insertion index a cursor carries: unsigned decimal digits, so a garbled cursor is a NumberFormatException (an IllegalArgumentException). */
    private static long lastIndex(String cursor) {
        return Integer.toUnsignedLong(Integer.parseUnsignedInt(Page.position(cursor)));
    }

    /** Snapshots held. */
    public synchronized int size() { return rows.size(); }
}
