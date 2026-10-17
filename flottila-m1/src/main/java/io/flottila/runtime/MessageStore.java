package io.flottila.runtime;

import io.flottila.core.*;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Store-and-forward + per-step payload snapshots. Payloads are content-addressed. */
public interface MessageStore {
    Uni<Void> save(Envelope e);
    /** One batch = one commit (one fsync); statements are fixed-shape so they stay prepared. */
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
     * Vert.x reactive PG client. payloads(hash, size, body) ON CONFLICT DO NOTHING (content-
     * addressed, deduplicated); messages rows reference body_hash; keys additionally indexed in
     * message_keys. All inserts use unnest($n::type[]) so the statement text is identical for
     * every batch size and the prepared-statement cache actually caches.
     */
    final class Postgres implements MessageStore {
        private static final String INSERT_PAYLOADS = "insert into payloads(hash, size, body) " +
                "select * from unnest($1::bytea[], $2::bigint[], $3::bytea[]) on conflict (hash) do nothing";
        private static final String INSERT_MESSAGES = "insert into messages(id, step, flow, headers, props, keys, attachments, body_hash) " +
                "select * from unnest($1::text[], $2::int[], $3::text[], $4::jsonb[], $5::jsonb[], $6::jsonb[], $7::jsonb[], $8::bytea[]) " +
                "on conflict (id, step) do nothing";
        private static final String INSERT_KEYS = "insert into message_keys(name, value, message_id, step) " +
                "select * from unnest($1::text[], $2::text[], $3::text[], $4::int[])";

        private final PgPool pool;

        public Postgres(PgPool pool) { this.pool = pool; }

        public Uni<Void> save(Envelope e) { return saveAll(List.of(e)); }

        public Uni<Void> saveAll(List<Envelope> batch) {
            if (batch.isEmpty()) return Uni.createFrom().voidItem();
            int n = batch.size();
            // payloads: distinct by content hash within the batch; DB dedups across batches
            Map<Long, Body> payloads = new LinkedHashMap<>();
            for (Envelope e : batch) payloads.putIfAbsent(e.body().hash(), e.body());
            io.vertx.core.buffer.Buffer[] pHash = new io.vertx.core.buffer.Buffer[payloads.size()];
            Long[] pSize = new Long[payloads.size()];
            io.vertx.core.buffer.Buffer[] pBody = new io.vertx.core.buffer.Buffer[payloads.size()];
            int i = 0;
            for (Body b : payloads.values()) {
                pHash[i] = hashBuffer(b.hash()); pSize[i] = b.size(); pBody[i] = io.vertx.core.buffer.Buffer.buffer(b.bytes()); i++;
            }

            String[] mId = new String[n]; Integer[] mStep = new Integer[n]; String[] mFlow = new String[n];
            JsonObject[] mHeaders = new JsonObject[n]; JsonObject[] mProps = new JsonObject[n];
            JsonObject[] mKeys = new JsonObject[n]; JsonObject[] mAtt = new JsonObject[n];
            io.vertx.core.buffer.Buffer[] mHash = new io.vertx.core.buffer.Buffer[n];
            java.util.List<String> kName = new java.util.ArrayList<>(), kValue = new java.util.ArrayList<>(), kId = new java.util.ArrayList<>();
            java.util.List<Integer> kStep = new java.util.ArrayList<>();
            for (i = 0; i < n; i++) {
                Envelope e = batch.get(i);
                mId[i] = e.id(); mStep[i] = e.step(); mFlow[i] = e.flow();
                mHeaders[i] = new JsonObject(new HashMap<String, Object>(e.headers().asMap()));
                mProps[i] = new JsonObject(new HashMap<String, Object>(e.props().asMap()));
                mKeys[i] = new JsonObject(new HashMap<String, Object>(e.keys().asMap()));
                JsonObject att = new JsonObject();
                e.attachments().forEach((name, part) -> att.put(name, new JsonObject()
                        .put("contentType", part.contentType()).put("size", part.body().size())));
                mAtt[i] = att;
                mHash[i] = hashBuffer(e.body().hash());
                for (var en : e.keys().asMap().entrySet()) {
                    kName.add(en.getKey()); kValue.add(en.getValue()); kId.add(e.id()); kStep.add(e.step());
                }
            }

            return pool.withTransaction(conn -> {
                Uni<RowSet<Row>> u = conn.preparedQuery(INSERT_PAYLOADS).execute(Tuple.of(pHash, pSize, pBody))
                        .chain(() -> conn.preparedQuery(INSERT_MESSAGES)
                                .execute(Tuple.tuple(List.of(mId, mStep, mFlow, mHeaders, mProps, mKeys, mAtt, mHash))));
                if (!kName.isEmpty()) u = u.chain(() -> conn.preparedQuery(INSERT_KEYS)
                        .execute(Tuple.of(kName.toArray(String[]::new), kValue.toArray(String[]::new),
                                kId.toArray(String[]::new), kStep.toArray(Integer[]::new))));
                return u;
            }).replaceWithVoid();
        }

        public Uni<Optional<Envelope>> load(String id, int step) {
            return pool.preparedQuery("select m.flow, m.headers, m.props, m.keys, p.body " +
                            "from messages m join payloads p on p.hash = m.body_hash where m.id = $1 and m.step = $2")
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

        private static io.vertx.core.buffer.Buffer hashBuffer(long hash) {
            return io.vertx.core.buffer.Buffer.buffer(8).appendLong(hash);
        }

        private static Map<String, String> stringMap(JsonObject j) {
            Map<String, String> m = new HashMap<>();
            j.forEach(en -> m.put(en.getKey(), String.valueOf(en.getValue())));
            return m;
        }
    }
}
