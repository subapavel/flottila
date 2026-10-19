package io.flottila.components.mock;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.testkit.ComponentContract;
import io.flottila.testkit.FakeRuntime;

import java.net.URI;
import java.util.Map;

/** The mock passes the same suite as the real components, standing in for the {@code http} scheme. */
class MockComponentContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new MockComponent("http", rt.blobs(), rt.timer());
    }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("partner-domestic", URI.create("http://localhost:8081/echo"), Map.of("url", "http://localhost:8081/echo"));
    }
}
