package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.vertx.core.json.JsonObject;

import java.time.Instant;

/** Monitoring record emitted after every stage. */
public record Snapshot(String id, String flow, int step, String stage, Instant ts, long latencyMicros,
                       long bodySize, JsonObject headers, String error) {

    public static Snapshot of(String stage, Envelope in, Envelope out, long latencyNanos, Throwable error) {
        Envelope e = out != null ? out : in;
        return new Snapshot(e.id(), e.flow(), out != null ? out.step() : in.step(), stage, Instant.now(),
                latencyNanos / 1000, e.body().size(), new JsonObject(new java.util.HashMap<String, Object>(e.headers())),
                error == null ? null : error.toString());
    }

    public JsonObject toJson() {
        return new JsonObject().put("id", id).put("flow", flow).put("step", step).put("stage", stage)
                .put("ts", ts.toString()).put("latencyMicros", latencyMicros).put("bodySize", bodySize)
                .put("headers", headers).put("error", error);
    }

    public static Snapshot fromJson(JsonObject j) {
        return new Snapshot(j.getString("id"), j.getString("flow"), j.getInteger("step"), j.getString("stage"),
                Instant.parse(j.getString("ts")), j.getLong("latencyMicros"), j.getLong("bodySize"),
                j.getJsonObject("headers"), j.getString("error"));
    }
}
