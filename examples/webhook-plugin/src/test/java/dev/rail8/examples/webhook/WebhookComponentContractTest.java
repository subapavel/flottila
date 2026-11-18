package dev.rail8.examples.webhook;

import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Timer;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.net.URI;
import java.util.Map;

/** The webhook component over real sockets on the loopback interface passes the contract suite every component passes. */
class WebhookComponentContractTest extends ComponentContract {

    static Vertx vertx;

    @BeforeAll
    static void start() { vertx = Vertx.vertx(); }

    @AfterAll
    static void stop() { vertx.close().await(); }

    @Override protected Component component() {
        return component(FakeRuntime.create().timer());
    }

    @Override protected Component component(Timer timer) {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB stream through the server and the client
        return new WebhookComponent(vertx, rt.blobs(), rt.ids(), timer);
    }

    /** Port 0: the consumer listens on a port the system picks. {@code flow} and {@code maxBodyBytes} are what the node sets. */
    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("in", URI.create("webhook://in"), Map.of("host", "127.0.0.1", "port", 0, "path", "/in",
                "maxBodyBytes", 1_000_000, "flow", "contract"));
    }

    /** The producer sends to the port the started consumer listens on. */
    @Override protected EndpointConfig producerEndpoint(Consumer started) {
        int port = ((WebhookComponent.WebhookConsumer) started).port();
        return new EndpointConfig("out", URI.create("webhook://out"), Map.of("url", "http://127.0.0.1:" + port + "/in", "maxBodyBytes", 1_000_000));
    }
}
