package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.Page;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DLQ over Postgres (ARCHITECTURE §6, §7): one row per parked message in V1's {@code dlq} with V3's columns, written
 * at once — a park is rare and must be durable before the failure surfaces — and never behind a foreign key, so a park
 * never fails on write ordering. The first park of a message wins. Listed oldest first by V3's {@code (parked_at, id)}
 * index; the cursor is the page's last position — {@code parked_at} in microseconds, Postgres' precision, and the id — so
 * a removal before it skips nothing.
 *
 * @since 0.1
 */
public final class PgDlq implements Dlq {

    private static final String COLUMNS = "id, flow, stage, step, error, parked_at, headers, props, keys, body_hash, hash_algorithm, body_size, body_key, versions";
    private static final String PARK = "insert into dlq(" + COLUMNS + ") values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) on conflict (id) do nothing";
    private static final String LIST = "select " + COLUMNS + " from dlq order by parked_at, id limit $1";
    private static final String LIST_AFTER = "select " + COLUMNS + " from dlq where (parked_at, id) > ($1, $2) order by parked_at, id limit $3";
    private static final Pattern CURSOR = Pattern.compile("(-?\\d+):(.+)");
    /**
     * Postgres' earliest timestamp, Julian day 0 (4714-11-24 BC), in microseconds since 1970. Its latest, 294276 AD, lies
     * beyond any long of microseconds since 1970 (294247 AD), so only the lower bound can be crossed.
     */
    private static final long PG_MIN_MICROS = ChronoUnit.MICROS.between(Instant.EPOCH, LocalDate.of(-4713, 11, 24).atStartOfDay(ZoneOffset.UTC).toInstant());
    private static final String GET = "select " + COLUMNS + " from dlq where id = $1";
    private static final String REMOVE = "delete from dlq where id = $1";

    private final Pool pool;

    public PgDlq(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<Void> park(Parked p) {
        Objects.requireNonNull(p, "parked");
        return pool.preparedQuery(PARK).execute(Tuple.wrap(new Object[] {
                p.messageId(), p.flow(), p.stepName(), p.step(), p.error(), p.parkedAt().atOffset(ZoneOffset.UTC),
                new JsonObject(new HashMap<>(p.headers().asMap())), json(p.props().asMap()), new JsonObject(new HashMap<>(p.keys().asMap())),
                PgMessageStore.bytes(p.bodyHash()), p.bodyHash().algorithm(), p.bodySize(), PgMessageStore.text(p.bodyKey()), new JsonObject(new HashMap<>(p.versions()))}))
                .mapEmpty();
    }

    @Override
    public Future<Page<Parked>> list(Optional<String> after, int limit) {
        Page.requireLimit(limit);
        Future<RowSet<Row>> rows = after.isPresent()
                ? pool.preparedQuery(LIST_AFTER).execute(position(after.get(), limit + 1))
                : pool.preparedQuery(LIST).execute(Tuple.of(limit + 1));
        return rows.map(found -> {
            List<Parked> out = new ArrayList<>();
            for (Row r : found) {
                if (out.size() == limit) return new Page<>(out, Optional.of(cursor(out.getLast())));   // the limit + 1st row: another page exists
                out.add(parked(r));
            }
            return new Page<>(out, Optional.<String>empty());
        });
    }

    /** The row's position: {@code parked_at} as read back (whole microseconds) and the id. */
    private static String cursor(Parked last) {
        return Page.cursor(ChronoUnit.MICROS.between(Instant.EPOCH, last.parkedAt()) + ":" + last.messageId());
    }

    /**
     * The (parked_at, id) a cursor carries, then the fetch size. A garbled cursor is an IllegalArgumentException — a number
     * out of the long range is a NumberFormatException, one too — and so is a time before Postgres' range, refused here
     * rather than by the query.
     */
    private static Tuple position(String cursor, int fetch) {
        Matcher m = CURSOR.matcher(Page.position(cursor));
        if (!m.matches()) throw new IllegalArgumentException("not a cursor of this DLQ");
        long micros = Long.parseLong(m.group(1));
        if (micros < PG_MIN_MICROS) throw new IllegalArgumentException("a cursor time before Postgres' range: " + micros + " µs");
        return Tuple.of(Instant.EPOCH.plus(micros, ChronoUnit.MICROS).atOffset(ZoneOffset.UTC), m.group(2), fetch);
    }

    @Override
    public Future<Optional<Parked>> get(String messageId) {
        return pool.preparedQuery(GET).execute(Tuple.of(messageId)).map(rows -> {
            for (Row r : rows) return Optional.of(parked(r));
            return Optional.empty();
        });
    }

    @Override
    public Future<Void> remove(String messageId) {
        return pool.preparedQuery(REMOVE).execute(Tuple.of(messageId)).mapEmpty();
    }

    private static Parked parked(Row r) {
        return new Parked(r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("stage"), r.getString("error"),
                r.getOffsetDateTime("parked_at").toInstant(), new Headers(strings(r.getJsonObject("headers"))), new Props(r.getJsonObject("props").getMap()),
                new Keys(strings(r.getJsonObject("keys"))), new Hash(r.getString("hash_algorithm"), r.getBuffer("body_hash").getLong(0)), r.getLong("body_size"),
                PgMessageStore.blobKey(r.getString("body_key")), strings(r.getJsonObject("versions")));
    }

    private static JsonObject json(Map<String, Object> props) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Object v = e.getValue();
            o.put(e.getKey(), v instanceof Number || v instanceof Boolean || v instanceof String ? v : String.valueOf(v));
        }
        return o;
    }

    private static Map<String, String> strings(JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        o.forEach(e -> m.put(e.getKey(), String.valueOf(e.getValue())));
        return m;
    }
}
