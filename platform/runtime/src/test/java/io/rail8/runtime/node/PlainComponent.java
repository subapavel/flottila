package io.rail8.runtime.node;

import io.rail8.api.Ack;
import io.rail8.api.Component;
import io.rail8.api.Consumer;
import io.rail8.api.EndpointConfig;
import io.rail8.api.Envelope;
import io.rail8.api.Health;
import io.rail8.api.Producer;
import io.vertx.core.Future;

import java.util.function.Function;

/**
 * A component of any scheme in a package that is neither the mock's nor the testkit's: what a node in a deployed
 * environment must accept — unless its scheme is {@code mock}. The node builds no endpoint before deploy (P1-09d), so
 * consumer and producer are refused; health is up.
 */
public final class PlainComponent implements Component {

    private final String scheme;

    public PlainComponent(String scheme) { this.scheme = scheme; }

    @Override public String scheme() { return scheme; }

    @Override public String configSchema() { return "{\"type\":\"object\"}"; }

    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Producer producer(EndpointConfig cfg) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

    @Override public String toString() { return "PlainComponent[" + scheme + "]"; }
}
