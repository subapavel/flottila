package io.flottila.components.mock.nested;

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
 * Test fixture of the node tests: a class in a package below the mock's ({@code io.flottila.components.mock..}) bound to
 * {@code sftp} — refused by a deployed node like one in the mock's package itself.
 */
public final class NestedMock implements Component {

    @Override public String scheme() { return "sftp"; }

    @Override public String configSchema() { return "{\"type\":\"object\"}"; }

    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Producer producer(EndpointConfig cfg) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
}
