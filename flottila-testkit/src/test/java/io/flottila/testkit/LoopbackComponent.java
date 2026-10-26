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
 * last started consumer's handler, called on a virtual thread of its own per delivery (D-057), and returns it; health is UP
 * once a consumer runs. A stopped consumer's handler is
 * gone at once and its {@code stop()} completes when the answers still outstanding have completed (D-056). Not the mock
 * component (P1-06), which lives in flottila-components/mock and passes the same contract suite.
 */
final class LoopbackComponent implements Component {

    private record Running(Function<Envelope, Future<Ack>> handler, ConsumerDrain drain) {}

    private volatile Running handler;
    private volatile boolean running;

    @Override public String scheme() { return "loopback"; }

    @Override public String configSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
        ConsumerDrain drain = new ConsumerDrain();
        return new Consumer() {
            @Override public Future<Void> start() { handler = new Running(h, drain); running = true; return Future.succeededFuture(); }
            @Override public Future<Void> stop() { running = false; handler = null; return drain.stop(); }
        };
    }

    @Override public Producer producer(EndpointConfig cfg) {
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                Running at = handler;
                if (at == null) return Future.failedFuture(new IllegalStateException("no consumer running"));
                return at.drain().deliver(at.handler(), e).compose(ack -> switch (ack) {
                    case Ack.Reply r -> Future.succeededFuture(r.reply());
                    case Ack.Accepted a -> Future.succeededFuture(e);
                    case Ack.Nack n -> Future.failedFuture(new io.flottila.api.NackException(n.reason(), n.retryable()));
                });
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override public Future<Health> health() {
        return Future.succeededFuture(running ? Health.up() : Health.down("no consumer running"));
    }
}
