package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.net.URI;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contract suite is only worth something if a broken component fails it. */
class ComponentContractSelfTest {

    /** Scheme does not match its endpoint, schema is not JSON, health lies, producer never delivers. */
    static final class BrokenComponent implements Component {
        @Override public String scheme() { return "http"; }
        @Override public String configSchema() { return "not json"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
            return new Consumer() {
                @Override public Future<Void> start() { return Future.succeededFuture(); }
                @Override public Future<Void> stop() { return Future.succeededFuture(); }
            };
        }
        @Override public Producer producer(EndpointConfig cfg) {
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) { return Future.succeededFuture(e); }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.down("never up")); }
    }

    static class BrokenComponentContractTest extends ComponentContract {
        @Override protected Component component() { return new BrokenComponent(); }
        @Override protected EndpointConfig endpoint() { return new EndpointConfig("b", URI.create("broken://x"), Map.of()); }
    }

    @Test
    void brokenComponentFailsTheSuite() {
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(BrokenComponentContractTest.class))
                .execute()
                .testEvents();
        long failed = results.failed().count();
        long total = results.started().count();
        assertTrue(total >= 5, "the contract suite has at least five cases, ran " + total);
        assertTrue(failed >= 4, "scheme, schema, health and delivery cases must fail, failed " + failed);
        assertEquals(0, results.skipped().count());
    }
}
