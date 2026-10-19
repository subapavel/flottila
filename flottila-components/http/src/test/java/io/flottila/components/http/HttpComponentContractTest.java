package io.flottila.components.http;

import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.testkit.ComponentContract;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.net.URI;
import java.util.Map;

/** The real component over real sockets on the loopback interface passes the same suite as the mock. */
class HttpComponentContractTest extends ComponentContract {

    static Vertx vertx;

    @BeforeAll
    static void start() { vertx = Vertx.vertx(); }

    @AfterAll
    static void stop() { vertx.close().await(); }

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB must stream through the server and the client
        return new HttpComponent(vertx, rt.blobs(), rt.ids());
    }

    /** Port 0: the kernel assigns the consumer's port at bind time — never a probed port another socket can take meanwhile. */
    @Override protected EndpointConfig endpoint() { return at(0); }

    /** The producer targets the port the started consumer actually listens on. */
    @Override protected EndpointConfig producerEndpoint(Consumer started) {
        return at(((HttpComponent.HttpConsumer) started).port());
    }

    private static EndpointConfig at(int port) {
        return new EndpointConfig("in", URI.create("http://127.0.0.1:" + port + "/in"),
                Map.of("host", "127.0.0.1", "port", port, "path", "/in", "url", "http://127.0.0.1:" + port + "/in", "maxBodyBytes", 1_000_000, "flow", "contract"));
    }
}
