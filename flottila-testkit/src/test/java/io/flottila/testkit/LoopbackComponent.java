package io.flottila.testkit;

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
 * Test-only component for the testkit's own tests: the producer hands the envelope straight to the
 * last started consumer's handler and returns it; health is UP once a consumer runs. Not the mock
 * component (P1-06), which lives in flottila-components/mock and passes the same contract suite.
 */
final class LoopbackComponent implements Component {

    private Function<Envelope, Future<Ack>> handler;
    private boolean running;

    @Override public String scheme() { return "loopback"; }

    @Override public String configSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
        return new Consumer() {
            @Override public Future<Void> start() { handler = h; running = true; return Future.succeededFuture(); }
            @Override public Future<Void> stop() { running = false; handler = null; return Future.succeededFuture(); }
        };
    }

    @Override public Producer producer(EndpointConfig cfg) {
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                if (handler == null) return Future.failedFuture(new IllegalStateException("no consumer running"));
                return handler.apply(e).map(ack -> ack instanceof Ack.Reply r ? r.reply() : e);
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override public Future<Health> health() {
        return Future.succeededFuture(running ? Health.up() : Health.down("no consumer running"));
    }
}
