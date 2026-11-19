package dev.rail8.components.direct;

import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Timer;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;

import java.net.URI;
import java.util.Map;

/**
 * The direct component passes the suite every component passes. The in endpoint carries {@code flow} and
 * {@code maxBodyBytes}, as the node sets them on every in endpoint; the out endpoint names the same address.
 */
class DirectComponentContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new DirectComponent(rt.blobs(), rt.ids(), rt.timer());
    }

    @Override protected Component component(Timer timer) {
        FakeRuntime rt = FakeRuntime.create();
        return new DirectComponent(rt.blobs(), rt.ids(), timer);
    }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("fulfil-in", URI.create("direct:/contract"),
                Map.of("address", "/contract", "flow", "fulfil", "maxBodyBytes", 1_048_576L));
    }

    @Override protected EndpointConfig producerEndpoint(Consumer started) {
        return new EndpointConfig("fulfil-out", URI.create("direct:/contract"), Map.of("address", "/contract"));
    }
}
