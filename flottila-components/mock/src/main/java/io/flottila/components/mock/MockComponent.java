package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The in-process mock of any scheme (ARCHITECTURE §7 "records what it was sent, replies from fixtures; selected per
 * environment"; §11): bound to the scheme it stands in for, so the flow file stays byte-identical, with one
 * {@link Behaviour} per endpoint from the flow's {@code *.test.yaml}. A producer records every envelope per endpoint and
 * answers with the reply of the consumer running on that same endpoint when there is one, else per behaviour: the fixture as
 * the next body (or the request echoed), after the delay through the injected timer, or a failure — a status or fault
 * as {@link NackException}, retryable as HTTP would be. Development and CI only: a deployed environment refuses to load
 * a mock binding (the deploy wiring's check).
 *
 * @since 0.1
 */
public final class MockComponent implements Component {

    static final String CONFIG_SCHEMA = "{\"type\":\"object\"}";   // the real endpoint's options are not the mock's business

    private final String scheme;
    private final BlobStore blobs;
    private final Timer timer;
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sends = new ConcurrentHashMap<>();
    private final List<Envelope> sent = new CopyOnWriteArrayList<>();
    private final Map<String, List<Envelope>> sentTo = new ConcurrentHashMap<>();
    private final Map<String, Function<Envelope, Future<Ack>>> handlers = new ConcurrentHashMap<>();   // by endpoint name: a consumer answers its own endpoint only

    public MockComponent(String scheme, BlobStore blobs, Timer timer) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** Binds {@code behaviour} to the endpoint named {@code endpoint}; an unbound endpoint echoes. */
    public MockComponent bind(String endpoint, Behaviour behaviour) {
        behaviours.put(Objects.requireNonNull(endpoint, "endpoint"), Objects.requireNonNull(behaviour, "behaviour"));
        return this;
    }

    /** Binds every entry of a {@code mocks} section ({@code endpoint → behaviour}), fixture paths against {@code baseDir}. */
    public MockComponent bindAll(Map<String, Object> mocks, Path baseDir) {
        mocks.forEach((endpoint, node) -> {
            if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("mocks." + endpoint + " must be an object");
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            bind(endpoint, Behaviour.from(copy, baseDir));
        });
        return this;
    }

    public List<Envelope> sent() { return List.copyOf(sent); }

    public List<Envelope> sentTo(String endpoint) { return List.copyOf(sentTo.getOrDefault(endpoint, List.of())); }

    @Override public String scheme() { return scheme; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
        Objects.requireNonNull(h, "handler");
        Objects.requireNonNull(cfg, "cfg");
        return new Consumer() {
            @Override public Future<Void> start() { handlers.put(cfg.name(), h); return Future.succeededFuture(); }
            @Override public Future<Void> stop() { handlers.remove(cfg.name(), h); return Future.succeededFuture(); }
        };
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        String name = cfg.name();
        List<Envelope> mine = sentTo.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        AtomicLong count = sends.computeIfAbsent(name, k -> new AtomicLong());
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                sent.add(e);
                mine.add(e);
                Behaviour b = behaviours.getOrDefault(name, Behaviour.ECHO);
                long n = count.incrementAndGet();
                return after(b.delay(), () -> {
                    if (b.failsOn(n)) return Future.failedFuture(failure(name, b.fail().orElseThrow()));
                    Function<Envelope, Future<Ack>> running = handlers.get(name);
                    if (running != null) return deliver(running, e);
                    return b.reply().map(fixture -> reply(e, fixture)).orElse(Future.succeededFuture(e));
                });
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(handlers.isEmpty() ? Health.down("no consumer running") : Health.up());
    }

    private <T> Future<T> after(java.time.Duration delay, java.util.function.Supplier<Future<T>> then) {
        if (delay.isZero()) return then.get();
        Promise<T> p = Promise.promise();
        timer.schedule(delay, () -> then.get().onComplete(p));
        return p.future();
    }

    private static Future<Envelope> deliver(Function<Envelope, Future<Ack>> handler, Envelope e) {
        return handler.apply(e).compose(ack -> switch (ack) {
            case Ack.Reply r -> Future.succeededFuture(r.reply());
            case Ack.Accepted a -> Future.succeededFuture(e);
            case Ack.Nack n -> Future.failedFuture(new NackException(n.reason(), n.retryable()));
        });
    }

    /** The fixture as the next step's body; the content type from the file's extension. */
    private Future<Envelope> reply(Envelope e, Path fixture) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(fixture);
        } catch (IOException x) {
            return Future.failedFuture(new IllegalStateException("mock reply fixture cannot be read: " + fixture, x));
        }
        return blobs.write(BlobKey.body(e.id(), e.step() + 1), Buffer.buffer(bytes), bytes.length)
                .map(body -> e.next(body).withHeader(Envelope.H_CONTENT_TYPE, contentType(fixture)));
    }

    static String contentType(Path fixture) {
        String name = fixture.getFileName().toString();
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    static NackException failure(String endpoint, Behaviour.Failure failure) {
        return switch (failure) {
            case Behaviour.Status s -> new NackException("mock " + endpoint + " answered " + s.code(), s.retryable());
            case Behaviour.Fault f -> new NackException("mock " + endpoint + " " + f.name().toLowerCase(java.util.Locale.ROOT), f.retryable());
        };
    }
}
