package io.flottila.testkit;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Timer;

import java.net.URI;
import java.util.Map;

/** The testkit's own component passes the contract suite every real and mock component must pass. */
class LoopbackComponentContractTest extends ComponentContract {

    @Override protected Component component() { return new LoopbackComponent(); }

    @Override protected Component component(Timer timer) { return new LoopbackComponent(timer); }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("loop", URI.create("loopback://local"), Map.of());
    }
}
