package dev.rail8.components.http;

import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.net.URI;
import java.util.Map;

/** The real component over real sockets on the loopback interface, its consumers on a shared port, passes the same suite as the mock. */
class HttpComponentContractTest extends ComponentContract {

    static Vertx vertx;
    static SharedHttpPort port;

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        port = SharedHttpPort.loopback(vertx, Map.of("http", HttpComponent.PREFIX)).await();
    }

    @AfterAll
    static void stop() {
        port.close().await();
        vertx.close().await();
    }

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB must stream through the server and the client
        dev.rail8.api.Timer onVertx = (delay, task) -> {
            long id = vertx.setTimer(Math.max(1, delay.toMillis()), fired -> task.run());
            return () -> vertx.cancelTimer(id);
        };
        return new HttpComponent(vertx, rt.blobs(), rt.ids(), onVertx, port);
    }

    /** The producer's cut runs on {@code timer}. */
    @Override protected Component component(dev.rail8.api.Timer timer) {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);
        return new HttpComponent(vertx, rt.blobs(), rt.ids(), timer, port);
    }

    /** {@code flow} and {@code maxBodyBytes} are what the node sets on every in endpoint. */
    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("in", URI.create("http://in"), Map.of("address", "/in", "maxBodyBytes", 1_000_000, "flow", "contract"));
    }

    /** The producer targets the URL of the started consumer's mount on the shared port. */
    @Override protected EndpointConfig producerEndpoint(Consumer started) {
        String url = ((HttpComponent.HttpConsumer) started).url().orElseThrow().toString();
        return new EndpointConfig("out", URI.create(url), Map.of("url", url, "maxBodyBytes", 1_000_000));
    }
}
