package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parked;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The DLQ over Postgres (ARCHITECTURE §6, §7): one row per parked message in V1's {@code dlq} with V3's columns, written
 * at once — a park is rare and must be durable before the failure surfaces — and never behind a foreign key, so a park
 * never fails on write ordering. The first park of a message wins.
 *
 * @since 0.1
 */
public final class PgDlq implements Dlq {

    private static final String COLUMNS = "id, flow, stage, step, error, parked_at, headers, props, keys, body_hash, hash_algorithm, body_size, body_key, versions";
    private static final String PARK = "insert into dlq(" + COLUMNS + ") values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) on conflict (id) do nothing";
    private static final String LIST = "select " + COLUMNS + " from dlq order by parked_at, id";
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
    public Future<List<Parked>> list() {
        return pool.query(LIST).execute().map(rows -> {
            List<Parked> out = new ArrayList<>();
            for (Row r : rows) out.add(parked(r));
            return List.copyOf(out);
        });
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
