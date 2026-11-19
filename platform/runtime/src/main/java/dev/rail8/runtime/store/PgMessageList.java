package dev.rail8.runtime.store;

import dev.rail8.core.record.Page;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The list of recent messages over Postgres: the messages whose arrival started in a time range, newest first, a page at a
 * time, each with its flow, status, start, duration and business keys. It reads only Postgres.
 *
 * <p>A message is listed by its arrival row (step 0) of its first attempt; a message whose arrival was not recorded (a flow's
 * recording level {@code errors} or {@code off}) is not listed. Its status is read when the page is read:
 * <ul>
 *   <li>{@link Status#FAILED} while it is parked in the DLQ;</li>
 *   <li>else {@link Status#RUNNING} while it has a work row: queued, waiting for its retry, running, held, or stopping after a cancel;</li>
 *   <li>else {@link Status#CANCELLED} when an operator cancelled it;</li>
 *   <li>else {@link Status#FAILED} when a step of its latest attempt failed (a sync or at-most-once message);</li>
 *   <li>else {@link Status#COMPLETED}, a sync or at-most-once message that is still running included, since nothing in
 *       Postgres marks such a run until a step of it fails.</li>
 * </ul>
 * Its duration runs from the start of its arrival to the end of its row that started last; its keys are those that row
 * carries. The cursor is the page's last start, in microseconds, and id. {@link #counts} counts the messages of a time range
 * and a flow by the same status.
 */
public final class PgMessageList {

    /** The longest time range one query may name. */
    public static final Duration MAX_RANGE = Duration.ofDays(31);

    /** Where a message stands; the admin API names it in lower case. */
    public enum Status { COMPLETED, FAILED, RUNNING, CANCELLED }

    /** A business key a listed message must carry. */
    public record Key(String name, String value) {
        public Key {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * What to list: the messages whose arrival started at {@code from} or later and before {@code to}, at most {@link #MAX_RANGE}
     * apart, of {@code flow}, in {@code status}, carrying {@code key} and with a recorded row of the step named {@code step} when
     * those are given. A range that is empty, reversed or too long is an {@link IllegalArgumentException}.
     */
    public record Query(Instant from, Instant to, Optional<String> flow, Optional<Status> status, Optional<Key> key, Optional<String> step) {
        public Query {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(step, "step");
            requireRange(from, to);
        }
    }

    /** A range that is empty, reversed or longer than {@link #MAX_RANGE} is an {@link IllegalArgumentException}. */
    private static void requireRange(Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw new IllegalArgumentException("the time range is longer than " + MAX_RANGE.toDays() + " days");
    }

    /** One listed message. */
    public record Listed(String id, String flow, Status status, Instant startedAt, long durationNanos, Map<String, String> keys) {
        public Listed {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startedAt, "startedAt");
            keys = Map.copyOf(keys);
        }
    }

    /** The status of the arrival row {@code m}, by the rules of the class comment. */
    private static final String STATUS = """
            case when exists (select 1 from dlq d where d.id = m.id) then 'failed'
                 when exists (select 1 from work w where w.flow = m.flow and w.id = m.id) then 'running'
                 when exists (select 1 from audit c where c.action = 'message.cancel' and c.subject = 'message/' || m.id) then 'cancelled'
                 when exists (select 1 from messages f where f.id = m.id and f.outcome = 'failed'
                              and not exists (select 1 from messages g where g.id = m.id and g.attempt > f.attempt)) then 'failed'
                 else 'completed' end""";

    private final Pool pool;

    public PgMessageList(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    /**
     * One page of {@code query}'s messages, newest first: by the start of their arrival, then by id, descending. {@code after} is
     * the previous page's {@link Page#next()} cursor, empty for the first page. A {@code limit} outside 1..{@link Page#MAX_LIMIT}
     * or a cursor this list did not hand out is an {@link IllegalArgumentException}, thrown.
     */
    public Future<Page<Listed>> list(Query query, Optional<String> after, int limit) {
        Objects.requireNonNull(query, "query");
        Page.requireLimit(limit);
        Optional<Cursor> cursor = after.map(PgMessageList::cursor);
        // a Postgres text holds no NUL: no row has such a flow, key or step name
        if (Stream.of(query.flow(), query.step(), query.key().map(Key::name), query.key().map(Key::value))
                .anyMatch(text -> text.isPresent() && text.get().indexOf('\0') >= 0)) {
            return Future.succeededFuture(new Page<>(List.of(), Optional.empty()));
        }
        List<Object> params = new ArrayList<>();
        StringBuilder where = arrivals(params, query.from(), query.to(), query.flow());
        query.key().ifPresent(key -> where.append(" and exists (select 1 from message_keys k where k.name = ").append(param(params, key.name()))
                .append(" and k.value = ").append(param(params, key.value())).append(" and k.message_id = m.id)"));
        query.step().ifPresent(step -> where.append(" and exists (select 1 from messages s where s.id = m.id and s.step_name = ").append(param(params, step)).append(')'));
        cursor.ifPresent(c -> where.append(" and (m.started_at, m.id) < (").append(param(params, c.startedAt().atOffset(ZoneOffset.UTC)))
                .append(", ").append(param(params, c.id())).append(')'));
        String status = query.status().map(s -> " where a.status = " + param(params, s.name().toLowerCase(Locale.ROOT))).orElse("");
        String sql = "select a.id, a.flow, a.started_at, a.status, l.started_at as last_started_at, l.wall_ns as last_wall_ns, l.keys as last_keys from ("
                + "select m.id, m.flow, m.started_at, " + STATUS + " as status from messages m where " + where + ") a "
                + "cross join lateral (select r.started_at, r.wall_ns, r.keys from messages r where r.id = a.id "
                + "order by r.started_at desc, r.attempt desc, r.step desc limit 1) l" + status
                + " order by a.started_at desc, a.id desc limit " + param(params, limit + 1);
        return pool.preparedQuery(sql).execute(Tuple.wrap(params)).map(rows -> {
            List<Listed> listed = new ArrayList<>();
            for (Row r : rows) {
                if (listed.size() == limit) {   // the limit + 1st row: another page exists
                    Listed last = listed.getLast();
                    return new Page<>(listed, Optional.of(Page.cursor(PgMessageStore.micros(last.startedAt()) + ":" + last.id())));
                }
                listed.add(listed(r));
            }
            return new Page<>(listed, Optional.<String>empty());
        });
    }

    /**
     * How many messages are in each status, every status named and counted from 0: the messages whose arrival started at
     * {@code from} or later and before {@code to}, of {@code flow} when it is given, as {@link #list} lists them. A range that
     * is empty, reversed or longer than {@link #MAX_RANGE} is an {@link IllegalArgumentException}, thrown.
     */
    public Future<Map<Status, Long>> counts(Instant from, Instant to, Optional<String> flow) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(flow, "flow");
        requireRange(from, to);
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) counts.put(s, 0L);
        if (flow.isPresent() && flow.get().indexOf('\0') >= 0) return Future.succeededFuture(counts);   // a Postgres text holds no NUL
        List<Object> params = new ArrayList<>();
        StringBuilder where = arrivals(params, from, to, flow);
        String sql = "select a.status, count(*) as n from (select " + STATUS + " as status from messages m where " + where + ") a group by a.status";
        return pool.preparedQuery(sql).execute(Tuple.wrap(params)).map(rows -> {
            for (Row r : rows) counts.put(Status.valueOf(r.getString("status").toUpperCase(Locale.ROOT)), r.getLong("n"));
            return counts;
        });
    }

    /**
     * The condition on the arrival rows {@code m} of first attempts that started at {@code from} or later and before {@code to},
     * of {@code flow} when it is given; its values are added to {@code params}.
     */
    private static StringBuilder arrivals(List<Object> params, Instant from, Instant to, Optional<String> flow) {
        StringBuilder where = new StringBuilder("m.step = 0 and m.attempt = 1 and m.started_at >= ").append(param(params, from.atOffset(ZoneOffset.UTC)))
                .append(" and m.started_at < ").append(param(params, to.atOffset(ZoneOffset.UTC)));
        flow.ifPresent(f -> where.append(" and m.flow = ").append(param(params, f)).append(" and m.step_name = 'ingress'"));
        return where;
    }

    private static Listed listed(Row r) {
        Instant started = r.getOffsetDateTime("started_at").toInstant();
        Instant lastStarted = r.getOffsetDateTime("last_started_at").toInstant();
        long duration = Math.max(0, Duration.between(started, lastStarted).toNanos() + r.getLong("last_wall_ns"));
        Map<String, String> keys = new LinkedHashMap<>();
        JsonObject carried = r.getJsonObject("last_keys");
        carried.forEach(e -> keys.put(e.getKey(), String.valueOf(e.getValue())));
        return new Listed(r.getString("id"), r.getString("flow"), Status.valueOf(r.getString("status").toUpperCase(Locale.ROOT)), started, duration, keys);
    }

    /** Adds {@code value} to {@code params} and answers its placeholder. */
    private static String param(List<Object> params, Object value) {
        params.add(value);
        return "$" + params.size();
    }

    private record Cursor(Instant startedAt, String id) {}

    /** The start and id a cursor carries; anything else is an IllegalArgumentException. */
    private static Cursor cursor(String cursor) {
        String position = Page.position(cursor);
        int colon = position.indexOf(':');
        if (colon < 0 || position.indexOf('\0') >= 0) throw new IllegalArgumentException("not a cursor of the list of messages");
        try {
            return new Cursor(PgMessageStore.startedAt(Long.parseLong(position.substring(0, colon))).toInstant(), position.substring(colon + 1));
        } catch (IllegalArgumentException garbled) {   // a start that is not a number, or out of range
            throw new IllegalArgumentException("not a cursor of the list of messages", garbled);
        }
    }
}
