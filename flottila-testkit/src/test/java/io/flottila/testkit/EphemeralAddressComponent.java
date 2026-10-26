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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test-only component whose consumer address exists only once the consumer has started — the shape of a transport that
 * binds an ephemeral port: {@link Consumer#start()} assigns a fresh address ({@code addr-1}, {@code addr-2}, …) and
 * registers the handler there, {@link Consumer#stop()} unregisters it. The endpoint options cannot name that address
 * beforehand; a producer reaches a consumer only through the option {@code address} of its own config and fails the send
 * with {@code "no consumer at " + address} when nothing runs there. Delivery and the {@link Ack} semantics are the mock
 * component's: a {@link Ack.Reply} answers with its reply, {@link Ack.Accepted} with the envelope sent, a {@link Ack.Nack}
 * fails the send with a {@link NackException} carrying its reason. Health is up while a consumer runs. {@link Consumer#stop()}
 * completes when the handler answers still outstanding have completed (D-056).
 */
final class EphemeralAddressComponent implements Component {

    private final AtomicInteger addresses = new AtomicInteger();
    private record Running(Function<Envelope, Future<Ack>> handler, ConsumerDrain drain) {}

    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final boolean stopKeepsTheAddressRegistered;

    /** The correct component: {@link Consumer#stop()} unregisters the consumer's address. */
    EphemeralAddressComponent() { this(false); }

    /**
     * @param stopKeepsTheAddressRegistered {@code true} builds a broken variant for the contract suite's self-test:
     *        {@link Consumer#stop()} forgets the consumer's {@code address()} but leaves its handler registered there, so a
     *        stopped consumer still receives and health stays up
     */
    EphemeralAddressComponent(boolean stopKeepsTheAddressRegistered) { this.stopKeepsTheAddressRegistered = stopKeepsTheAddressRegistered; }

    @Override public String scheme() { return "ephemeral"; }

    @Override public String configSchema() {
        return "{\"type\":\"object\",\"properties\":{\"address\":{\"type\":\"string\"}}}";
    }

    @Override public EphemeralConsumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(cfg, "cfg");
        return new EphemeralConsumer(Objects.requireNonNull(handler, "handler"));
    }

    @Override public Producer producer(EndpointConfig cfg) {
        Object address = Objects.requireNonNull(cfg, "cfg").options().get("address");
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                Running at = running.get(String.valueOf(address));   // no address: "null", never assigned
                if (at == null) return Future.failedFuture(new IllegalStateException("no consumer at " + address));
                return at.drain().track(at.handler().apply(e)).compose(ack -> switch (ack) {
                    case Ack.Reply r -> Future.succeededFuture(r.reply());
                    case Ack.Accepted a -> Future.succeededFuture(e);
                    case Ack.Nack n -> Future.failedFuture(new NackException(n.reason(), n.retryable()));
                });
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override public Future<Health> health() {
        return Future.succeededFuture(running.isEmpty() ? Health.down("no consumer running") : Health.up());
    }

    /** A consumer whose address is assigned at {@link #start()}; {@code null} before it and after {@link #stop()}. */
    final class EphemeralConsumer implements Consumer {

        private final Running registered;
        private volatile String address;

        private EphemeralConsumer(Function<Envelope, Future<Ack>> handler) { this.registered = new Running(handler, new ConsumerDrain()); }

        /** The address the consumer listens on; {@code null} unless it runs. */
        String address() { return address; }

        @Override public Future<Void> start() {
            String assigned = "addr-" + addresses.incrementAndGet();
            running.put(assigned, registered);
            address = assigned;
            return Future.succeededFuture();
        }

        @Override public Future<Void> stop() {
            String was = address;
            if (was != null && !stopKeepsTheAddressRegistered) running.remove(was, registered);
            address = null;
            return registered.drain().stop();
        }
    }
}
