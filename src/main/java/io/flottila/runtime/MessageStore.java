package io.flottila.runtime;

import io.flottila.core.*;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Store-and-forward + per-step payload snapshots. */
public interface MessageStore {
    Uni<Void> save(Envelope e);
    /** One batch = one multi-row INSERT (implementation permitting), one commit, one fsync. */
    Uni<Void> saveAll(List<Envelope> batch);
    Uni<Optional<Envelope>> load(String id, int step);

    final class InMemory implements MessageStore {
        private final Map<String, Envelope> map = new ConcurrentHashMap<>();
        public Uni<Void> save(Envelope e) { map.put(e.id() + "/" + e.step(), e); return Uni.createFrom().voidItem(); }
        public Uni<Void> saveAll(List<Envelope> batch) { batch.forEach(e -> map.put(e.id() + "/" + e.step(), e)); return Uni.createFrom().voidItem(); }
        public Uni<Optional<Envelope>> load(String id, int step) {
            return Uni.createFrom().item(Optional.ofNullable(map.get(id + "/" + step)));
        }
    }

    /**
     * Vert.x reactive PG client. Envelope columns: headers/props/keys/attachments as jsonb,
     * body as bytea (Spilled is read back into bytes; Empty is a zero-length bytea).
     * Keys additionally go to message_keys(name, value) for the indexed operator search.
     */
    final class Postgres implements MessageStore {
        private final PgPool pool;

        public Postgres(PgPool pool) { this.pool = pool; }

        public Uni<Void> save(Envelope e) { return saveAll(List.of(e)); }

        public Uni<Void> saveAll(List<Envelope> batch) {
            if (batch.isEmpty()) return Uni.createFrom().voidItem();
            StringBuilder msgSql = new StringBuilder("insert into messages(id, step, flow, headers, props, keys, attachments, body) values ");
            List<Object> msgParams = new ArrayList<>();
            StringBuilder keySql = new StringBuilder("insert into message_keys(name, value, message_id, step) values ");
            List<Object> keyParams = new ArrayList<>();
            int m = 1, k = 1;
            for (Envelope e : batch) {
                if (m > 1) msgSql.append(',');
                msgSql.append("($").append(m++).append(",$").append(m++).append(",$").append(m++).append(",$").append(m++)
                      .append(",$").append(m++).append(",$").append(m++).append(",$").append(m++).append(",$").append(m++).append(')');
                JsonObject att = new JsonObject();
                e.attachments().forEach((name, part) -> att.put(name, new JsonObject()
                        .put("contentType", part.contentType()).put("size", part.body().size())));
                msgParams.add(e.id()); msgParams.add(e.step()); msgParams.add(e.flow());
                msgParams.add(new JsonObject(new HashMap<String, Object>(e.headers().asMap())));
                msgParams.add(new JsonObject(new HashMap<String, Object>(e.props().asMap())));
                msgParams.add(new JsonObject(new HashMap<String, Object>(e.keys().asMap())));
                msgParams.add(att);
                msgParams.add(io.vertx.core.buffer.Buffer.buffer(e.body().bytes()));
                for (var en : e.keys().asMap().entrySet()) {
                    if (k > 1) keySql.append(',');
                    keySql.append("($").append(k++).append(",$").append(k++).append(",$").append(k++).append(",$").append(k++).append(')');
                    keyParams.add(en.getKey()); keyParams.add(en.getValue()); keyParams.add(e.id()); keyParams.add(e.step());
                }
            }
            msgSql.append(" on conflict (id, step) do nothing");
            return pool.withTransaction(conn -> {
                Uni<RowSet<Row>> u = conn.preparedQuery(msgSql.toString()).execute(Tuple.from(msgParams));
                if (!keyParams.isEmpty()) u = u.chain(() -> conn.preparedQuery(keySql.toString()).execute(Tuple.from(keyParams)));
                return u;
            }).replaceWithVoid();
        }

        public Uni<Optional<Envelope>> load(String id, int step) {
            return pool.preparedQuery("select flow, headers, props, keys, body from messages where id = $1 and step = $2")
                    .execute(Tuple.of(id, step))
                    .onItem().transform(rs -> {
                        for (Row row : rs) {
                            io.vertx.core.buffer.Buffer buf = row.getBuffer("body") == null ? null : row.getBuffer("body").getDelegate();
                            Body body = buf == null || buf.length() == 0 ? Body.empty() : Body.of(buf.getBytes());
                            return Optional.of(new Envelope(id, row.getString("flow"), step,
                                    new Headers(stringMap(row.getJsonObject("headers"))),
                                    new Props(row.getJsonObject("props").getMap()),
                                    new Keys(stringMap(row.getJsonObject("keys"))),
                                    body, Map.of(), new Views()));
                        }
                        return Optional.<Envelope>empty();
                    });
        }

        private static Map<String, String> stringMap(JsonObject j) {
            Map<String, String> m = new HashMap<>();
            j.forEach(en -> m.put(en.getKey(), String.valueOf(en.getValue())));
            return m;
        }
    }
}
