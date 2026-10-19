package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.vertx.core.Future;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The mock binding of a scheme for flow tests (ARCHITECTURE §11: a flow file is byte-identical everywhere, the
 * mocks live next to it). A producer records every envelope it is sent, per endpoint, and answers: with the
 * reply of the consumer running on that same endpoint (the loopback of the contract suite), else
 * with {@link #replyWith(Function)} (identity by default), or with the next failure queued by
 * {@link #failNext(int, Supplier)} — the way a test plays "partner down" for a retry. Health is up while a
 * consumer runs. Passes {@link ComponentContract}.
 *
 * @since 0.1
 */
public final class RecordingComponent implements Component {

    private final String scheme;
    private final List<Envelope> sent = new CopyOnWriteArrayList<>();
    private final Map<String, List<Envelope>> sentTo = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Supplier<? extends Throwable>> failures = new ConcurrentLinkedDeque<>();
    private final AtomicInteger producersBuilt = new AtomicInteger();
    private volatile Function<Envelope, Envelope> reply = e -> e;
    private final Map<String, Function<Envelope, Future<Ack>>> handlers = new ConcurrentHashMap<>();   // by endpoint name

    public RecordingComponent(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
    }

    /** What the producers were sent, in order. */
    public List<Envelope> sent() { return List.copyOf(sent); }

    /** What the producer of the endpoint {@code name} was sent, in order. */
    public List<Envelope> sentTo(String name) { return List.copyOf(sentTo.getOrDefault(name, List.of())); }

    /** Producers built so far, across endpoints. */
    public int producersBuilt() { return producersBuilt.get(); }

    /** The reply a send gets while no consumer runs; the function sees the envelope as sent. */
    public RecordingComponent replyWith(Function<Envelope, Envelope> reply) {
        this.reply = Objects.requireNonNull(reply, "reply");
        return this;
    }

    /** The next {@code times} sends fail with a fresh exception from {@code failure}, before any reply. */
    public RecordingComponent failNext(int times, Supplier<? extends Throwable> failure) {
        Objects.requireNonNull(failure, "failure");
        if (times < 0) throw new IllegalArgumentException("times must not be negative, was " + times);
        for (int i = 0; i < times; i++) failures.addLast(failure);
        return this;
    }

    /** Failures queued and not yet used. */
    public int failuresLeft() { return failures.size(); }

    @Override public String scheme() { return scheme; }

    @Override public String configSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

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
        producersBuilt.incrementAndGet();
        List<Envelope> mine = sentTo.computeIfAbsent(cfg.name(), k -> new CopyOnWriteArrayList<>());
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                sent.add(e);
                mine.add(e);
                Supplier<? extends Throwable> failure = failures.pollFirst();
                if (failure != null) return Future.failedFuture(failure.get());
                Function<Envelope, Future<Ack>> running = handlers.get(cfg.name());
                if (running != null) {
                    return running.apply(e).compose(ack -> switch (ack) {
                        case Ack.Reply r -> Future.succeededFuture(r.reply());
                        case Ack.Accepted a -> Future.succeededFuture(e);
                        case Ack.Nack n -> Future.failedFuture(new NackException(n.reason(), n.retryable()));
                    });
                }
                return Future.succeededFuture(reply.apply(e));
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(handlers.isEmpty() ? Health.down("no consumer running") : Health.up());
    }
}
