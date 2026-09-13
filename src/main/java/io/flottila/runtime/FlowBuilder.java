package io.flottila.runtime;

import io.flottila.core.*;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** JSON flow config → plug() chain. Dependencies are captured at build time; Runtime is not kept. */
public class FlowBuilder {
    private static final Logger log = LoggerFactory.getLogger(FlowBuilder.class);
    private final Runtime rt;

    public FlowBuilder(Runtime rt) { this.rt = rt; }

    public Flow build(JsonObject cfg) {
        String flow = cfg.getString("name");
        URI from = URI.create(cfg.getString("from"));
        Component.Consumer consumer = rt.component(from.getScheme()).consumer(from, flow);

        // "delivery": at-least-once (default) | at-most-once; "persistence": "none" or {batchSize,batchMs}
        boolean atMostOnce = "at-most-once".equals(cfg.getString("delivery", "at-least-once"));
        Object persistence = cfg.getValue("persistence");
        JsonObject p = persistence instanceof JsonObject jo ? jo : new JsonObject();
        BatchingWriter writer = "none".equals(persistence) ? null
                : new BatchingWriter(rt.messages(), p.getInteger("batchSize", 100), p.getInteger("batchMs", 10),
                        p.getInteger("commitConcurrency", 4));

        var bus = rt.vertx().eventBus();

        // partitionBy names an extractKey key; its xpath is evaluated at enqueue time (the
        // extractKey stage has not run yet at ingress). Absent -> null -> no ordering guarantee.
        java.util.function.Function<Envelope, String> partition = e -> null;
        String partitionBy = cfg.getString("partitionBy");
        if (partitionBy != null) {
            JsonArray stepCfgs = cfg.getJsonArray("steps", new JsonArray());
            for (int i = 0; i < stepCfgs.size(); i++) {
                JsonObject s = stepCfgs.getJsonObject(i);
                if ("extractKey".equals(s.getString("type")) && partitionBy.equals(s.getString("key"))) {
                    String xp = s.getString("xpath"); Xml xml = rt.xml();
                    partition = e -> { try { return xml.evalString(xp, e); } catch (RuntimeException ex) { return null; } };
                }
            }
        }

        // consumers with an irreversible ACK await this before acknowledging; request/reply ignores it.
        // At-least-once with a work queue: durable save + enqueue -> ACK; a worker runs the pipeline.
        Duration ackTimeout = Duration.ofMillis(cfg.getLong("ackTimeoutMs", 5000L));
        WorkQueue workQueue = rt.workQueue();
        boolean queued = workQueue != null && writer != null && !atMostOnce;
        Component.DurableIngress durable;
        if (queued) {
            var part = partition;
            durable = e -> {
                Envelope e1 = e.withStep(1);
                return writer.save(e1)
                        .chain(() -> workQueue.enqueue(e1.id(), flow, 1, part.apply(e1)))
                        // the worker starts at stage 1, so publish the ingress snapshot here
                        // (directly — the monitor lambda would save the step-1 row a second time)
                        .invoke(() -> bus.publish(MonitorVerticle.ADDRESS, Snapshot.of("ingress", e, e1, 0, null).toJson()))
                        .ifNoItem().after(ackTimeout).failWith(() -> new BatchingWriter.OverloadedException(
                                "ingress not durable within " + ackTimeout.toMillis() + " ms"));
            };
        } else if (writer != null && !atMostOnce) {
            // TODO: this path writes the step-1 row twice (here and via the ingress stage's
            // monitor save when the consumer emits directly) — absorbed by ON CONFLICT DO NOTHING.
            durable = e -> writer.save(e.withStep(1)).ifNoItem().after(ackTimeout)
                    .failWith(() -> new BatchingWriter.OverloadedException(
                            "ingress save not durable within " + ackTimeout.toMillis() + " ms"));
        } else {
            durable = e -> Uni.createFrom().voidItem();
        }
        consumer.durableIngress(durable, queued);
        StageContext.Monitor monitor = (stage, in, out, latency, error) -> {
            Snapshot s = Snapshot.of(stage, in, out, latency, error);
            if (writer != null && out != null)                                   // fire-and-forget, every stage
                writer.save(out).subscribe().with(x -> {}, t -> log.error("snapshot save failed for {}", out.id(), t));
            bus.publish(MonitorVerticle.ADDRESS, s.toJson());                    // async, never awaited
        };
        Dlq dlq = new Dlq(consumer, atMostOnce, rt.dlqStore());
        JsonObject r = cfg.getJsonObject("retry", new JsonObject());
        var retry = new StageContext.RetryPolicy(r.getInteger("attempts", 0),
                Duration.ofMillis(r.getLong("initialMs", 100L)), Duration.ofMillis(r.getLong("maxMs", 2000L)));
        var ctx = new StageContext(flow, monitor, dlq, retry, cfg.getInteger("concurrency", 16));
        var noRetry = new StageContext(flow, monitor, dlq, StageContext.RetryPolicy.none(), ctx.concurrency());

        List<Flow.Named> stages = new ArrayList<>();
        // stage 0: ingress — pure identity; its snapshot (= what arrived) is fire-and-forget like
        // every other stage. Durability-before-ACK lives in the consumer (DurableIngress above).
        stages.add(new Flow.Named("ingress", Stages.step("ingress", e -> Uni.createFrom().item(e), noRetry)));

        JsonArray steps = cfg.getJsonArray("steps", new JsonArray());
        for (int i = 0; i < steps.size(); i++) {
            JsonObject s = steps.getJsonObject(i);
            String type = s.getString("type");
            String name = s.getString("name", type + "-" + (i + 1));
            if (type.equals("route")) {
                stages.add(new Flow.Named(name, route(name, s, noRetry)));
                continue;
            }
            Step step = switch (type) {
                case "validate"   -> validate(s.getString("required"));
                case "extractKey" -> extractKey(s.getString("key"), s.getString("xpath"));
                case "xslt"       -> rt.xml().xslt(s.getString("stylesheet"));
                case "to"         -> to(s);
                default -> throw new IllegalArgumentException("unknown step type " + type);
            };
            // I/O steps retry; local validation/transform failing again is pointless
            stages.add(new Flow.Named(name, Stages.step(name, step, type.equals("to") ? ctx : noRetry)));
        }
        return new Flow(flow, consumer, List.copyOf(stages));
    }

    private Step validate(String requiredXPath) {
        Xml xml = rt.xml(); Executor cpu = rt.cpuPool();
        return e -> Uni.createFrom().item(() -> {
            String v = xml.evalString(requiredXPath, e);
            if (v == null || v.isBlank()) throw new IllegalArgumentException("validation failed: " + requiredXPath + " missing");
            return e;
        }).runSubscriptionOn(cpu);
    }

    /** Business correlation key → keys → indexed monitoring metadata. */
    private Step extractKey(String key, String xpath) {
        Xml xml = rt.xml(); Executor cpu = rt.cpuPool();
        return e -> Uni.createFrom().item(() -> {
            String v = xml.evalString(xpath, e);
            return v == null ? e : e.withKey(key, v);
        }).runSubscriptionOn(cpu);
    }

    /**
     * Content-based router: first matching XPath predicate wins; each branch sets the configured
     * prop and is a full stage named "route:<branch>" (snapshot, step++), so the timeline shows
     * the taken branch without reading props.
     */
    private Stage route(String name, JsonObject s, StageContext ctx) {
        Xml xml = rt.xml();
        Key<String> prop = new Key<>(s.getString("prop"));
        List<Stages.Route> routes = new ArrayList<>();
        JsonArray rs = s.getJsonArray("routes", new JsonArray());
        for (int i = 0; i < rs.size(); i++) {
            JsonObject r = rs.getJsonObject(i);
            String when = r.getString("when");
            String value = r.getString("set");
            routes.add(new Stages.Route(e -> "true".equals(xml.evalString(when, e)),
                    Stages.step(name + ":" + value, e -> Uni.createFrom().item(e.with(prop, value)), ctx)));
        }
        String otherwise = s.getString("otherwise");
        return Stages.route(routes, Stages.step(name + ":" + otherwise, e -> Uni.createFrom().item(e.with(prop, otherwise)), ctx));
    }

    /** Egress. Either a single "uri" string, or a map of prop value → uri selected by "byProp". */
    private Step to(JsonObject s) {
        JsonObject opts = new JsonObject().put("forwardHeaders", s.getJsonArray("forwardHeaders", new JsonArray()));
        if (s.getValue("uri") instanceof String u) {
            URI uri = URI.create(u);
            Component.Producer p = rt.component(uri.getScheme()).producer(uri, opts);
            return p::send;
        }
        Key<String> prop = new Key<>(s.getString("byProp"));
        Map<String, Component.Producer> producers = new HashMap<>();
        s.getJsonObject("uri").forEach(en -> {
            URI uri = URI.create((String) en.getValue());
            producers.put(en.getKey(), rt.component(uri.getScheme()).producer(uri, opts));
        });
        return e -> {
            String v = e.get(prop).orElse(null);
            Component.Producer p = v == null ? null : producers.get(v);
            if (p == null) return Uni.createFrom().failure(
                    new IllegalStateException("no target for " + prop.name() + "=" + v));
            return p.send(e);
        };
    }
}
