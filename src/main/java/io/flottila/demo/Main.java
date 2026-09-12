package io.flottila.demo;

import io.flottila.components.HttpComponent;
import io.flottila.runtime.*;
import io.flottila.runtime.Runtime;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.Router;
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
        Runtime rt = new Runtime(vertx, Map.of("http", http), new MessageStore.InMemory(),
                new MonitoringStore.InMemory(), cpu, blocking, new Xml(cpu));

        // fake backend the flow posts to
        Router echo = http.router(8081);
        echo.post("/echo").handler(ctx -> ctx.response().putHeader("content-type", "application/xml")
                .endAndForget("<ack id=\"" + ctx.request().getHeader("x-flottila-id") + "\">ok</ack>"));
        vertx.createHttpServer().requestHandler(echo).listen(8081).await().indefinitely();

        JsonObject cfg = new JsonObject(new String(Main.class.getResourceAsStream("/flows/invoice.json").readAllBytes(), StandardCharsets.UTF_8));
        Flow flow = new FlowBuilder(rt).build(cfg);

        // admin / monitoring API on the ingress port — registered before the server starts
        Router admin = http.router(8080);
        admin.get("/monitor").handler(ctx -> {
            JsonArray a = new JsonArray();
            rt.monitoring().all().forEach((id, tl) -> a.add(new JsonObject().put("id", id).put("flow", tl.get(0).flow())
                    .put("steps", tl.size()).put("key", tl.get(tl.size() - 1).headers().getString("key.invoiceId"))
                    .put("error", tl.stream().filter(s -> s.error() != null).map(Snapshot::error).findFirst().orElse(null))));
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
        admin.get("/dlq").handler(ctx -> ctx.response().putHeader("content-type", "application/json")
                .endAndForget(new JsonArray(rt.monitoring().all().values().stream()
                        .flatMap(tl -> tl.stream().filter(s -> s.error() != null)).map(Snapshot::toJson).toList()).encodePrettily()));

        vertx.deployVerticle(new MonitorVerticle(rt.monitoring())).await().indefinitely();
        vertx.deployVerticle(new FlowVerticle(flow)).await().indefinitely();
        log.info("Flottila prototype up. POST XML to http://localhost:8080/in, inspect http://localhost:8080/monitor");
    }
}
