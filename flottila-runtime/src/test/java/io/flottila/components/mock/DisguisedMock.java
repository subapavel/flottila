package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.vertx.core.Future;

import java.util.function.Function;

/**
 * Test fixture of the node tests (runtime test tree, not the mock module): a class in the mock's package bound to a real
 * scheme, {@code http} — a deployed node refuses it by its package although its scheme is not {@code mock} (D-057).
 */
public final class DisguisedMock implements Component {

    @Override public String scheme() { return "http"; }

    @Override public String configSchema() { return "{\"type\":\"object\"}"; }

    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Producer producer(EndpointConfig cfg) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
}
