package io.flottila.api;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P1-09g (D-072): {@link Component#listensOn} — a component that does not say where its consumers listen holds no port. P2-02L
 * (D-083): {@code Component.polls} — a component that does not say its consumers poll is never run under a lease. P2-02D
 * (D-084): {@code Component.dedupeBy} — a component that names no dedupe prop has its arrivals never deduped.
 *
 * <p>The shape assumed (P2-02D): {@code default Optional<Key<String>> dedupeBy(EndpointConfig cfg)}, empty.
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
    void dedupeByIsEmptyByDefault() {
        EndpointConfig cfg = new EndpointConfig("drop", URI.create("sftp://partner.example:22/outbox"),
                Map.of("intervalMs", 10_000, "dedupeBy", "sftp.fileKey"));

        Optional<Key<String>> dedupe = BARE.dedupeBy(cfg);
        assertEquals(Optional.empty(), dedupe, "by default no dedupe key, whatever the endpoint's options say");
    }
}
