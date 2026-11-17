package dev.rail8.api;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The defaults of {@link Component}: a component that does not say where its consumers listen holds no port, one that does
 * not say its consumers poll is never run under a lease, and one that names no target gets a limit per {@code to}.
 */
class ComponentTest {

    /** A component implementing only what it must: it polls, subscribes or connects out, as far as anyone can tell. */
    static final Component BARE = new Component() {
        @Override public String scheme() { return "bare"; }

        @Override public String configSchema() { return "{}"; }

        @Override
        public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            throw new UnsupportedOperationException("no consumer in this test");
        }

        @Override
        public Producer producer(EndpointConfig cfg) {
            throw new UnsupportedOperationException("no producer in this test");
        }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    };

    @Test
    void listensOnIsEmptyByDefault() {
        EndpointConfig cfg = new EndpointConfig("in", URI.create("http://127.0.0.1:8080/in"), Map.of("host", "0.0.0.0", "port", 8080));

        assertEquals(Optional.empty(), BARE.listensOn(cfg), "by default no address, whatever host and port the config names");
    }

    @Test
    void pollsIsFalseByDefault() {
        EndpointConfig cfg = new EndpointConfig("drop", URI.create("sftp://partner.example:22/outbox"), Map.of("intervalMs", 10_000));

        assertFalse(BARE.polls(cfg), "by default a consumer is not leased, whatever its endpoint looks like");
    }

    @Test
    void targetIsEmptyByDefault() {
        EndpointConfig cfg = new EndpointConfig("partner", URI.create("https://partner.example:443/orders"), Map.of("url", "https://partner.example/orders"));

        assertEquals(Optional.empty(), BARE.target(cfg), "by default no target: the builder gives its sends a limit of their own");
    }
}
