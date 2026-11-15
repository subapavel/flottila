package io.flottila.core.dlq;

import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DLQ in memory: tests and dry runs only — a deployed at-least-once flow parks in Postgres. Listed oldest first
 * (parkedAt, then id) like the Postgres one; the cursor is the position (parkedAt, id) of the page's last row, so a
 * removal before it skips nothing.
 *
 * @since 0.1
 */
public final class InMemoryDlq implements Dlq {

    /** A row's place in the listing: parkedAt as seconds and nanos, then the id. */
    private record Position(long seconds, int nanos, String id) {
        static Position of(Parked p) { return new Position(p.parkedAt().getEpochSecond(), p.parkedAt().getNano(), p.messageId()); }
    }

    private static final Comparator<Position> ORDER = Comparator.comparingLong(Position::seconds).thenComparingInt(Position::nanos).thenComparing(Position::id);
    private static final Pattern CURSOR = Pattern.compile("(-?\\d+):(\\d+):(.+)");

    private final Map<String, Parked> rows = new HashMap<>();   // guarded by this
    private final NavigableMap<Position, Parked> listing = new TreeMap<>(ORDER);   // guarded by this

    @Override
    public synchronized Future<Void> park(Parked parked) {
        Objects.requireNonNull(parked, "parked");
        if (rows.putIfAbsent(parked.messageId(), parked) == null) listing.put(Position.of(parked), parked);
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Page<Parked>> list(Optional<String> after, int limit) {
        Page.requireLimit(limit);
        NavigableMap<Position, Parked> rest = after.map(c -> listing.tailMap(position(c), false)).orElse(listing);
        Iterator<Parked> it = rest.values().iterator();
        List<Parked> items = new ArrayList<>();
        while (items.size() < limit && it.hasNext()) items.add(it.next());
        Optional<String> next = it.hasNext() ? Optional.of(cursor(items.getLast())) : Optional.empty();
        return Future.succeededFuture(new Page<>(items, next));
    }

    @Override
    public synchronized Future<Optional<Parked>> get(String messageId) {
        return Future.succeededFuture(Optional.ofNullable(rows.get(messageId)));
    }

    @Override
    public synchronized Future<Void> remove(String messageId) {
        Parked gone = rows.remove(messageId);
        if (gone != null) listing.remove(Position.of(gone));
        return Future.succeededFuture();
    }

    public synchronized int size() { return rows.size(); }

    private static String cursor(Parked last) {
        Position p = Position.of(last);
        return Page.cursor(p.seconds() + ":" + p.nanos() + ":" + p.id());
    }

    /** The position a cursor carries; a garbled one is an IllegalArgumentException (a number out of range is a NumberFormatException, one too). */
    private static Position position(String cursor) {
        Matcher m = CURSOR.matcher(Page.position(cursor));
        if (!m.matches()) throw new IllegalArgumentException("not a cursor of this DLQ");
        return new Position(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)), m.group(3));
    }
}
