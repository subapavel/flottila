package io.flottila.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Immutable message. Every step returns a new Envelope; body and parsed views are shared by reference.
 * Four kinds of data, deliberately kept apart: headers (transport), props (flow state, typed keys),
 * keys (business correlation, indexed), attachments (multipart parts, stored by reference).
 */
public record Envelope(String id, String flow, int step,
                       Headers headers, Props props, Keys keys,
                       Body body, Map<String, Part> attachments, Views views) {

    public Envelope { attachments = Map.copyOf(attachments); }

    public static final String H_CONTENT_TYPE = "content-type";
    /** Set on replayed messages; a prop — flow state, never leaves the runtime. */
    public static final Key<String> REPLAY_OF = new Key<>("replayOf");

    public static Envelope ingress(String flow, Map<String, String> headers, Body body) {
        return new Envelope(UUID.randomUUID().toString(), flow, 0,
                new Headers(headers), Props.empty(), Keys.empty(), body, Map.of(), new Views());
    }

    /** New body → new (empty) view cache; headers/props/keys/attachments carried over. */
    public Envelope next(Body newBody) {
        return new Envelope(id, flow, step, headers, props, keys, newBody, attachments, new Views());
    }
    public Envelope next(Body newBody, Map<String, String> extraHeaders) {
        return next(newBody).withHeaders(extraHeaders);
    }

    public Envelope withHeader(String k, String v) {
        return new Envelope(id, flow, step, headers.with(k, v), props, keys, body, attachments, views);
    }
    public Envelope withHeaders(Map<String, String> extra) {
        return new Envelope(id, flow, step, headers.withAll(extra), props, keys, body, attachments, views);
    }
    public <T> Envelope with(Key<T> key, T value) {
        return new Envelope(id, flow, step, headers, props.with(key, value), keys, body, attachments, views);
    }
    public Envelope withKey(String name, String value) {
        return new Envelope(id, flow, step, headers, props, keys.with(name, value), body, attachments, views);
    }
    public Envelope withAttachment(Part part) {
        Map<String, Part> a = new HashMap<>(attachments); a.put(part.name(), part);
        return new Envelope(id, flow, step, headers, props, keys, body, a, views);
    }
    public Envelope withStep(int s) { return new Envelope(id, flow, s, headers, props, keys, body, attachments, views); }
    public Envelope withId(String newId) { return new Envelope(newId, flow, step, headers, props, keys, body, attachments, views); }

    public <T> T view(Class<T> type, Function<Body, T> parser) { return views.get(type, () -> parser.apply(body)); }

    public String header(String k) { return headers.get(k); }
    public <T> Optional<T> get(Key<T> key) { return props.get(key); }
    public String key(String name) { return keys.get(name); }
}
