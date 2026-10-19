package io.flottila.testkit;

import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;

import java.net.URI;
import java.util.Map;

/**
 * A component whose consumer address is assigned only at start (an ephemeral port) passes the contract suite: the endpoint
 * cannot name the address, so every producer is built from the started consumer's address.
 */
class EphemeralAddressContractTest extends ComponentContract {

    @Override protected Component component() { return new EphemeralAddressComponent(); }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("in", URI.create("ephemeral://in"), Map.of());   // no address: none exists before start
    }

    @Override protected EndpointConfig producerEndpoint(Consumer started) {
        String address = ((EphemeralAddressComponent.EphemeralConsumer) started).address();
        return new EndpointConfig("in", URI.create("ephemeral://in"), Map.of("address", address));
    }
}
