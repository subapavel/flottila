package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Durable dead letter queue (state whose loss loses messages goes to DB). */
public interface DlqStore {

    Uni<Void> park(String stage, Envelope e, Throwable t);
    Uni<List<JsonObject>> list();

    final class InMemory implements DlqStore {
        private final List<JsonObject> rows = new CopyOnWriteArrayList<>();
        public Uni<Void> park(String stage, Envelope e, Throwable t) {
            rows.add(row(stage, e, t)); return Uni.createFrom().voidItem();
        }
        public Uni<List<JsonObject>> list() { return Uni.createFrom().item(List.copyOf(rows)); }
    }

    final class Postgres implements DlqStore {
        private static final String PARK = "insert into dlq(id, flow, stage, step, error, headers, body_hash) " +
                "values ($1, $2, $3, $4, $5, $6, $7) on conflict (id) do nothing";
        private final PgPool pool;

        public Postgres(PgPool pool) { this.pool = pool; }

        public Uni<Void> park(String stage, Envelope e, Throwable t) {
            return pool.preparedQuery(PARK).execute(Tuple.tuple(List.of(e.id(), e.flow(), stage, e.step(),
                            t.toString(), new JsonObject(new HashMap<String, Object>(e.headers().asMap())),
                            io.vertx.core.buffer.Buffer.buffer(8).appendLong(e.body().hash()))))
                    .replaceWithVoid();
        }

        public Uni<List<JsonObject>> list() {
            return pool.query("select id, flow, stage, step, error, headers, parked_at from dlq " +
                            "order by parked_at desc limit 500").execute()
                    .onItem().transform(rs -> {
                        List<JsonObject> out = new ArrayList<>();
                        for (Row r : rs) out.add(new JsonObject()
                                .put("id", r.getString("id")).put("flow", r.getString("flow"))
                                .put("stage", r.getString("stage")).put("step", r.getInteger("step"))
                                .put("error", r.getString("error")).put("headers", r.getJsonObject("headers"))
                                .put("parkedAt", r.getOffsetDateTime("parked_at").toString()));
                        return out;
                    });
        }
    }

    private static JsonObject row(String stage, Envelope e, Throwable t) {
        return new JsonObject().put("id", e.id()).put("flow", e.flow()).put("stage", stage)
                .put("step", e.step()).put("error", t.toString())
                .put("headers", new JsonObject(new HashMap<String, Object>(e.headers().asMap())));
    }
}
