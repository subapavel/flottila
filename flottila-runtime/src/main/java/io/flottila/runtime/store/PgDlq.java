package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
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
 * at once — a park is rare and must be durable before the failure surfaces — with no foreign key to {@code messages} (a
 * park never waits on the step rows), but its body's kept ref must be committed first (D-062 persist → park, D-096, D-105):
 * without it the park fails with {@link BlobNotFoundException}. A park adds no record (CORE-3 item 10): it writes its row
 * only while the {@code body_refs} row its {@code body_key} names exists with that ref's {@code journal_segments} row, and
 * holds both for key share against retention's and the sweeper's deletes for its one statement, so a DLQ row implies a
 * durable body (D-062). A pre-V12 {@code bodies} row alone admits no park. The first park
 * of a message wins. Listed oldest first by V3's {@code (parked_at, id)} index; the cursor is the page's last position —
 * {@code parked_at} in microseconds, Postgres' precision, and the id — so a removal before it skips nothing.
 *
 * @since 0.1
 */
public final class PgDlq implements Dlq {

    private static final String COLUMNS = "id, flow, stage, step, error, parked_at, headers, props, keys, body_hash, hash_algorithm, body_size, body_key, versions";
    /**
     * The park, one statement (D-096, CORE-3 item 10): {@code b} holds the {@code body_refs} row {@code body_key} names
     * ({@code $15..$17}) and its segment row for key share — retention's batch and the sweeper's hold the rows they select for
     * update, so the park either waits for that batch and, after a delete, finds no row, or holds the rows first and the batch
     * passes them — and {@code ins} writes the DLQ row only from them ({@code on conflict} skips an id parked already). Answers the rows found; {@code ins} runs to completion
     * unread, as every data-modifying CTE does.
     */
    private static final String PARK = """
            with b as materialized (select 1 from body_refs r join journal_segments s on s.node = r.node and s.segment = r.segment
                                     where r.message_id = $15 and r.step = $16 and r.part = $17 for key share),
            ins as (insert into dlq(%s) select $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14 from b
                    on conflict (id) do nothing)
            select count(*) from b""".formatted(COLUMNS);
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

    /**
     * Writes {@code p}'s row unless its id is parked already, and only while the kept ref its {@code body_key} names exists with
     * its segment row: without one (a pre-V12 {@code bodies} row admits none) the park fails with {@link BlobNotFoundException}
     * naming the key and writes nothing (D-062, D-096, CORE-4c2). A retention batch holding that ref waits the park until it
     * committed or rolled back.
     */
    @Override
    public Future<Void> park(Parked p) {
        Objects.requireNonNull(p, "parked");
        BlobKey body = p.bodyKey();
        return pool.preparedQuery(PARK).execute(Tuple.wrap(new Object[] {
                p.messageId(), p.flow(), p.stepName(), p.step(), p.error(), p.parkedAt().atOffset(ZoneOffset.UTC),
                new JsonObject(new HashMap<>(p.headers().asMap())), json(p.props().asMap()), new JsonObject(new HashMap<>(p.keys().asMap())),
                PgMessageStore.bytes(p.bodyHash()), p.bodyHash().algorithm(), p.bodySize(), PgMessageStore.text(body), new JsonObject(new HashMap<>(p.versions())),
                body.messageId(), body.step(), body.part()}))
                .map(rows -> {
                    if (rows.iterator().next().getLong(0) == 0) throw new BlobNotFoundException(body);
                    return null;
                });
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
