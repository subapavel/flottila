package io.flottila.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Immutable message. Every step returns a new Envelope.
 * Parsed views (XdmNode, JsonObject, ...) are memoized per body and shared by reference
 * between envelopes that carry the same body — safe because nothing is ever mutated.
 */
public record Envelope(String id, String flow, int step, Map<String, String> headers, Body body,
                       ConcurrentHashMap<Class<?>, Object> views) {

    public static final String H_CONTENT_TYPE = "content-type";
    public static final String H_REPLAY_OF   = "flottila.replayOf";

    public static Envelope ingress(String flow, Map<String, String> headers, Body body) {
        return new Envelope(UUID.randomUUID().toString(), flow, 0, Map.copyOf(headers), body, new ConcurrentHashMap<>());
    }

    /** New body → new (empty) view cache. */
    public Envelope next(Body newBody) {
        return new Envelope(id, flow, step, headers, newBody, new ConcurrentHashMap<>());
    }
    public Envelope next(Body newBody, Map<String, String> extraHeaders) {
        return next(newBody).withHeaders(extraHeaders);
    }
    /** Same body → shared view cache. */
    public Envelope withHeader(String k, String v) {
        Map<String, String> h = new HashMap<>(headers); h.put(k, v);
        return new Envelope(id, flow, step, Map.copyOf(h), body, views);
    }
    public Envelope withHeaders(Map<String, String> extra) {
        Map<String, String> h = new HashMap<>(headers); h.putAll(extra);
        return new Envelope(id, flow, step, Map.copyOf(h), body, views);
    }
    public Envelope withStep(int s) { return new Envelope(id, flow, s, headers, body, views); }
    public Envelope withId(String newId) { return new Envelope(newId, flow, step, headers, body, views); }

    @SuppressWarnings("unchecked")
    public <T> T view(Class<T> type, Function<Body, T> parser) {
        return (T) views.computeIfAbsent(type, t -> parser.apply(body));
    }

    public String header(String k) { return headers.get(k); }
}
