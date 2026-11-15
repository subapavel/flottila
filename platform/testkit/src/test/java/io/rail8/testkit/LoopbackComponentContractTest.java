package io.rail8.testkit;

import io.rail8.api.Component;
import io.rail8.api.EndpointConfig;
import io.rail8.api.Timer;

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
