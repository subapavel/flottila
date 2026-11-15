package io.rail8.components.mock;

import io.rail8.api.Component;
import io.rail8.api.EndpointConfig;
import io.rail8.testkit.ComponentContract;
import io.rail8.testkit.FakeRuntime;

import java.net.URI;
import java.util.Map;

/** The mock passes the same suite as the real components, standing in for the {@code http} scheme. */
class MockComponentContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new MockComponent("http", rt.blobs(), rt.timer());
    }

    /** P2-15b: the mock's sends are cut on the timer it is built with. */
    @Override protected Component component(io.rail8.api.Timer timer) {
        return new MockComponent("http", FakeRuntime.create().blobs(), timer);
    }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("partner-domestic", URI.create("http://localhost:8081/echo"), Map.of("url", "http://localhost:8081/echo"));
    }
}
