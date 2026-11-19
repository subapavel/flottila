package dev.rail8.cli;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Deadline;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Settings;
import dev.rail8.components.mock.MockComponent;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The {@code direct} component of a {@code rail8 test} node. A call between flows runs for real, on the direct module's
 * component: a call to an address a flow of the package or of a dependency serves runs that flow. An endpoint the case's mocks
 * name is the mock's instead, in and out, as any other endpoint a case mocks. The settings, the internal address and the health are the real
 * component's, so the deploy checks the addresses and the cycles of calls as a node does.
 *
 * <p>What a real endpoint saw is recorded as the mock records it: every envelope sent on an out endpoint ({@link #sent},
 * {@link #sentTo}), retries included, and every envelope an in endpoint's handler was given ({@link #delivered}). The lists are
 * read without a lock.
 *
 * <p>{@link #ingest} sends a case's fixture to a flow fed by {@code direct} through a real producer, as a caller at depth 0 would.
 */
final class Calls implements Component, FlowTests.Door {

    /** The flow of the caller that sends a case's fixture: the test itself, named nowhere in the output. */
    static final String CALLER = "rail8-test";

    private final Component real;
    private final MockComponent mock;
    private final Set<String> mocked;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final List<Envelope> sent = new CopyOnWriteArrayList<>();
    private final Map<String, List<Envelope>> sentTo = new ConcurrentHashMap<>();
    private final Map<String, List<Envelope>> delivered = new ConcurrentHashMap<>();
    private final Map<String, String> served = new ConcurrentHashMap<>();   // by in endpoint name: the address its consumer serves

    /**
     * @param real the calls that run: the direct module's component
     * @param mock the {@code direct} mock, bound to the case's behaviours
     * @param mocked the endpoints the case's mocks name: the mock's
     * @param blobs where {@link #ingest} stores a fixture's bytes
     * @param ids the ids of the callers {@link #ingest} plays
     */
    Calls(Component real, MockComponent mock, Set<String> mocked, BlobStore blobs, IdGenerator ids) {
        this.real = Objects.requireNonNull(real, "real");
        this.mock = Objects.requireNonNull(mock, "mock");
        this.mocked = Set.copyOf(mocked);
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override public String scheme() { return real.scheme(); }

    @Override public Settings settings() { return real.settings(); }

    @Override public Optional<String> internalAddress(EndpointConfig cfg) { return real.internalAddress(cfg); }

    @Override public Future<Health> health() { return real.health(); }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(handler, "handler");
        if (mocked.contains(cfg.name())) return mock.consumer(cfg, handler);
        List<Envelope> given = delivered.computeIfAbsent(cfg.name(), k -> new CopyOnWriteArrayList<>());
        Consumer consumer = real.consumer(cfg, e -> {
            given.add(e);   // recorded before the handler runs: whoever reads its answer finds it
            return handler.apply(e);
        });
        served.put(cfg.name(), real.internalAddress(cfg).orElseThrow());
        return consumer;
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        if (mocked.contains(cfg.name())) return mock.producer(cfg);
        Producer producer = real.producer(cfg);
        List<Envelope> mine = sentTo.computeIfAbsent(cfg.name(), k -> new CopyOnWriteArrayList<>());
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                record(e);
                return producer.send(e);
            }

            @Override public Future<Envelope> send(Envelope e, Deadline d) {
                record(e);
                return producer.send(e, d);
            }

            @Override public Future<Void> close() { return producer.close(); }

            private void record(Envelope e) {
                sent.add(e);
                mine.add(e);
            }
        };
    }

    /** Every envelope sent on a real out endpoint, in order. */
    List<Envelope> sent() { return List.copyOf(sent); }

    /** The envelopes sent on the out endpoint named {@code endpoint}: the mock's sends when the case mocks it. */
    List<Envelope> sentTo(String endpoint) {
        if (mocked.contains(endpoint)) return mock.sentTo(endpoint);
        return List.copyOf(sentTo.getOrDefault(endpoint, List.of()));
    }

    /**
     * Sends {@code bytes} with {@code headers} to the flow served on the in endpoint named {@code endpoint} as a caller at depth 0
     * would: a message of a new id and of the flow {@value #CALLER}, its body stored under {@code BlobKey.body(id, 0)}, sent through
     * a real producer of the endpoint's address. On an endpoint the case mocks, the mock's {@link MockComponent#ingest}.
     *
     * @return what the call gave the caller as a {@link Ack.Reply}; failed as the call failed
     * @throws IllegalStateException no consumer was built on {@code endpoint}
     */
    @Override
    public Future<Ack> ingest(String endpoint, Headers headers, Buffer bytes) {
        if (mocked.contains(endpoint)) return mock.ingest(endpoint, headers, bytes);
        String address = served.get(endpoint);
        if (address == null) throw new IllegalStateException("no consumer of direct runs on endpoint " + endpoint);
        Producer caller = real.producer(new EndpointConfig(endpoint, URI.create("direct://" + endpoint), Map.of("address", address)));
        String id = ids.next();
        return blobs.write(BlobKey.body(id, 0), bytes, bytes.length())
                .compose(body -> caller.send(Envelope.ingress(id, CALLER, headers, body)))
                .map(Ack.Reply::new);
    }

    @Override
    public List<Envelope> delivered(String endpoint) {
        if (mocked.contains(endpoint)) return mock.delivered(endpoint);
        return List.copyOf(delivered.getOrDefault(endpoint, List.of()));
    }

    /** The mock's for an endpoint the case mocks; none on a real one, which takes no file. */
    @Override
    public Collection<String> undelivered(String endpoint) {
        return mocked.contains(endpoint) ? mock.undelivered(endpoint) : List.of();
    }

    /** The mock's for an endpoint the case mocks; none on a real one, which takes no file. */
    @Override
    public Collection<String> remaining(String endpoint) {
        return mocked.contains(endpoint) ? mock.remaining(endpoint) : List.of();
    }
}
