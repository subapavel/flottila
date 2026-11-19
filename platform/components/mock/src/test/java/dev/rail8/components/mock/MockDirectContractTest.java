package dev.rail8.components.mock;

import dev.rail8.api.Component;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Timer;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;

import java.net.URI;
import java.util.Map;

/** The mock passes the same suite as the real components, standing in for the {@code direct} scheme of calls between flows. */
class MockDirectContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new MockComponent("direct", rt.blobs(), rt.timer());
    }

    @Override protected Component component(Timer timer) {
        return new MockComponent("direct", FakeRuntime.create().blobs(), timer);
    }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("fulfil-in", URI.create("direct:/contract"), Map.of("address", "/contract"));
    }
}
