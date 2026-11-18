package dev.rail8.api;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The default of {@link Consumer#broken()}: a consumer that implements only its start and stop never reports a break. */
class ConsumerTest {

    @Test
    void aConsumerThatSaysNothingNeverBreaks() {
        Consumer bare = new Consumer() {
            @Override public Future<Void> start() { return Future.succeededFuture(); }
            @Override public Future<Void> stop() { return Future.succeededFuture(); }
        };
        bare.start();
        assertFalse(bare.broken().isComplete(), "pending while it works");
        bare.stop();
        assertFalse(bare.broken().isComplete(), "and after its stop");
    }
}
