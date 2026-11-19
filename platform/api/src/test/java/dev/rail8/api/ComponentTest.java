package dev.rail8.api;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import dev.rail8.api.settings.Settings;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The defaults of {@link Component}: a component that does not say where its consumers listen holds no port, one that does
 * not say its consumers poll is never run under a lease, one that names no target gets a limit per {@code to}, one that names
 * no node-local address reaches outside the node, and one that describes no settings is judged by its {@code configSchema()},
 * which takes any object unless it says more.
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

    @Test
    void internalAddressIsEmptyByDefault() {
        EndpointConfig cfg = new EndpointConfig("orders", URI.create("direct:/orders"), Map.of("address", "/orders"));

        assertEquals(Optional.empty(), BARE.internalAddress(cfg), "by default no node-local address, whatever the config names: the endpoint reaches outside the node");
    }

    @Test
    void aComponentDescribesNoSettingsByDefault() {
        assertEquals(Settings.NONE, BARE.settings(), "undescribed: the deploy judges its options by configSchema()");
    }

    @Test
    void theConfigSchemaTakesAnyObjectByDefault() {
        Component described = new Component() {
            @Override public String scheme() { return "described"; }

            @Override public Settings settings() { return Settings.of(); }

            @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }

            @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException(); }

            @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
        };
        assertEquals("{\"type\":\"object\"}", described.configSchema(), "a described component needs no schema of its own");
    }
}
