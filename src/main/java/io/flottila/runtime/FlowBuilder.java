package io.flottila.runtime;

import io.flottila.core.*;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** JSON flow config → plug() chain. Dependencies are captured at build time; Runtime is not kept. */
public class FlowBuilder {
    private final Runtime rt;

    public FlowBuilder(Runtime rt) { this.rt = rt; }

    public Flow build(JsonObject cfg) {
        String flow = cfg.getString("name");
        URI from = URI.create(cfg.getString("from"));
        Component.Consumer consumer = rt.component(from.getScheme()).consumer(from, flow);

        StageContext.Monitor monitor = (stage, in, out, latency, error) -> {
            Snapshot s = Snapshot.of(stage, in, out, latency, error);
            if (out != null) rt.messages().save(out).subscribe().with(x -> {});   // payload snapshot per step
            rt.vertx().eventBus().publish(MonitorVerticle.ADDRESS, s.toJson());  // async, never awaited
        };
        Dlq dlq = new Dlq(consumer);
        JsonObject r = cfg.getJsonObject("retry", new JsonObject());
        var retry = new StageContext.RetryPolicy(r.getInteger("attempts", 0),
                Duration.ofMillis(r.getLong("initialMs", 100L)), Duration.ofMillis(r.getLong("maxMs", 2000L)));
        var ctx = new StageContext(flow, monitor, dlq, retry, cfg.getInteger("concurrency", 16));
        var noRetry = new StageContext(flow, monitor, dlq, StageContext.RetryPolicy.none(), ctx.concurrency());

        List<Flow.Named> stages = new ArrayList<>();
        // stage 0: ingress — identity, but produces the step-1 snapshot (= what actually arrived)
        stages.add(new Flow.Named("ingress", Stages.step("ingress", e -> Uni.createFrom().item(e), noRetry)));

        JsonArray steps = cfg.getJsonArray("steps", new JsonArray());
        for (int i = 0; i < steps.size(); i++) {
            JsonObject s = steps.getJsonObject(i);
            String type = s.getString("type");
            String name = s.getString("name", type + "-" + (i + 1));
            Step step = switch (type) {
                case "validate"   -> validate(s.getString("required"));
                case "extractKey" -> extractKey(s.getString("key"), s.getString("xpath"));
                case "xslt"       -> rt.xml().xslt(s.getString("stylesheet"));
                case "to"         -> to(URI.create(s.getString("uri")));
                default -> throw new IllegalArgumentException("unknown step type " + type);
            };
            // I/O steps retry; local validation/transform failing again is pointless
            stages.add(new Flow.Named(name, Stages.step(name, step, type.equals("to") ? ctx : noRetry)));
        }
        return new Flow(flow, consumer, List.copyOf(stages));
    }

    private Step validate(String requiredXPath) {
        return e -> Uni.createFrom().item(() -> {
            String v = rt.xml().evalString(requiredXPath, e);
            if (v == null || v.isBlank()) throw new IllegalArgumentException("validation failed: " + requiredXPath + " missing");
            return e;
        }).runSubscriptionOn(rt.cpuPool());
    }

    /** Business correlation key → header → indexed monitoring metadata. */
    private Step extractKey(String key, String xpath) {
        return e -> Uni.createFrom().item(() -> {
            String v = rt.xml().evalString(xpath, e);
            return v == null ? e : e.withHeader("key." + key, v);
        }).runSubscriptionOn(rt.cpuPool());
    }

    private Step to(URI uri) {
        Component.Producer p = rt.component(uri.getScheme()).producer(uri);
        return p::send;
    }
}
