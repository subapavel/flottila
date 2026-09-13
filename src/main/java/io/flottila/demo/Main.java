package io.flottila.demo;

import io.flottila.components.HttpComponent;
import io.flottila.runtime.*;
import io.flottila.runtime.Runtime;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Milestone 1 demo: HTTP → validate → extractKey → XSLT → HTTP (echo backend), snapshot after every
 * stage, retry + DLQ, replay from a chosen stage. In-memory stores; swap for Postgres behind the interfaces.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        ExecutorService cpu = Executors.newFixedThreadPool(java.lang.Runtime.getRuntime().availableProcessors());
        ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
        HttpComponent http = new HttpComponent(vertx, 16 * 1024 * 1024);

        boolean postgres = "postgres".equals(System.getenv().getOrDefault("FLOTTILA_STORE", "memory"));
        PgPool pool = postgres ? pgPool(vertx) : null;
        MessageStore messages = postgres ? new MessageStore.Postgres(pool) : new MessageStore.InMemory();
        WorkQueue workQueue = postgres ? new WorkQueue.Postgres(pool) : null;
        DlqStore dlqStore = postgres ? new DlqStore.Postgres(pool) : new DlqStore.InMemory();
        Runtime rt = new Runtime(vertx, Map.of("http", http), messages,
                new MonitoringStore.InMemory(), cpu, blocking, new Xml(cpu), workQueue, dlqStore);

        // fake backend the flow posts to; FLOTTILA_ECHO=off simulates the partner being down
        // ack body is deliberately static (id only in the header) so identical runs produce
        // identical ack payloads — content-addressing dedups them
        if (!"off".equals(System.getenv().getOrDefault("FLOTTILA_ECHO", "on"))) {
            Router echo = http.router(8081);
            echo.post("/echo").handler(ctx -> ctx.response().putHeader("content-type", "application/xml")
                    .putHeader("x-flottila-id", ctx.request().getHeader("x-flottila-id"))
                    .endAndForget("<ack partner=\"domestic\">ok</ack>"));
            echo.post("/echo-foreign").handler(ctx -> ctx.response().putHeader("content-type", "application/xml")
                    .putHeader("x-flottila-id", ctx.request().getHeader("x-flottila-id"))
                    .endAndForget("<ack partner=\"foreign\">ok</ack>"));
            vertx.createHttpServer().requestHandler(echo).listen(8081).await().indefinitely();
        } else log.warn("echo backend OFF (FLOTTILA_ECHO=off) — partner calls will fail");

        JsonObject cfg = new JsonObject(new String(Main.class.getResourceAsStream("/flows/invoice.json").readAllBytes(), StandardCharsets.UTF_8));
        // bench override: FLOTTILA_PERSISTENCE = none | <batchSize>/<batchMs>
        String persistence = System.getenv("FLOTTILA_PERSISTENCE");
        if ("none".equals(persistence)) cfg.put("persistence", "none");
        else if (persistence != null) {
            String[] parts = persistence.split("/");
            cfg.put("persistence", new JsonObject().put("batchSize", Integer.parseInt(parts[0])).put("batchMs", Integer.parseInt(parts[1])));
        }
        Flow flow = new FlowBuilder(rt).build(cfg);

        // admin / monitoring API on the ingress port — registered before the server starts
        Router admin = http.router(8080);
        admin.get("/monitor").handler(ctx -> {
            String key = ctx.request().getParam("key"), value = ctx.request().getParam("value");
            JsonArray a = new JsonArray();
            rt.monitoring().all().forEach((id, tl) -> {
                Snapshot last = tl.get(tl.size() - 1);
                if (key != null && !java.util.Objects.equals(last.keys().getString(key), value)) return;
                a.add(new JsonObject().put("id", id).put("flow", last.flow())
                        .put("steps", tl.size()).put("keys", last.keys()).put("props", last.props())
                        .put("error", tl.stream().filter(s -> s.error() != null).map(Snapshot::error).findFirst().orElse(null)));
            });
            ctx.response().putHeader("content-type", "application/json").endAndForget(a.encodePrettily());
        });
        admin.get("/monitor/:id").handler(ctx -> {
            JsonArray a = new JsonArray();
            rt.monitoring().timeline(ctx.pathParam("id")).forEach(s -> a.add(s.toJson()));
            ctx.response().putHeader("content-type", "application/json").endAndForget(a.encodePrettily());
        });
        admin.get("/monitor/:id/payload/:step").handler(ctx ->
            rt.messages().load(ctx.pathParam("id"), Integer.parseInt(ctx.pathParam("step"))).subscribe().with(opt ->
                opt.ifPresentOrElse(e -> ctx.response().putHeader("content-type", "application/xml").endAndForget(e.body().text()),
                                    () -> ctx.response().setStatusCode(404).endAndForget("no snapshot"))));
        admin.post("/monitor/:id/replay/:fromStage").handler(ctx -> {
            int from = Integer.parseInt(ctx.pathParam("fromStage"));
            rt.messages().load(ctx.pathParam("id"), from).subscribe().with(opt -> opt.ifPresentOrElse(e -> {
                flow.replay(e, from).subscribe().with(out -> {}, t -> log.error("replay failed", t));
                ctx.response().endAndForget("replaying " + e.id() + " from stage " + from + " (" + flow.stages().get(from).name() + ")");
            }, () -> ctx.response().setStatusCode(404).endAndForget("no snapshot at step " + from)));
        });
        admin.get("/dlq").handler(ctx -> rt.dlqStore().list().subscribe().with(rows ->
                ctx.response().putHeader("content-type", "application/json")
                        .endAndForget(new JsonArray(rows).encodePrettily()),
                t -> ctx.response().setStatusCode(500).endAndForget(t.getMessage())));
        admin.post("/dlq/:id/replay/:stage").handler(ctx -> {
            int from = Integer.parseInt(ctx.pathParam("stage"));
            rt.messages().load(ctx.pathParam("id"), from).subscribe().with(opt -> opt.ifPresentOrElse(e -> {
                flow.replay(e, from).subscribe().with(out -> {}, t -> log.error("dlq replay failed", t));
                ctx.response().endAndForget("replaying " + e.id() + " from stage " + from + " (" + flow.stages().get(from).name() + ")");
            }, () -> ctx.response().setStatusCode(404).endAndForget("no snapshot at step " + from)));
        });

        vertx.deployVerticle(new MonitorVerticle(rt.monitoring())).await().indefinitely();
        vertx.deployVerticle(new FlowVerticle(flow)).await().indefinitely();
        if (rt.workQueue() != null)
            vertx.deployVerticle(new WorkerVerticle(flow, rt.workQueue(), rt.messages())).await().indefinitely();
        log.info("Flottila prototype up. POST XML to http://localhost:8080/in, inspect http://localhost:8080/monitor");
    }

    /** FLOTTILA_STORE = memory (default) | postgres (docker-compose.yml, db/user/pass flottila). */
    private static PgPool pgPool(Vertx vertx) {
        PgConnectOptions co = new PgConnectOptions().setHost("localhost")
                .setPort(Integer.parseInt(System.getenv().getOrDefault("FLOTTILA_PG_PORT", "5432")))
                .setDatabase("flottila").setUser("flottila").setPassword("flottila");
        return PgPool.pool(vertx, co.setCachePreparedStatements(true), new PoolOptions().setMaxSize(8));
    }
}
