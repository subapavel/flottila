package io.flottila.api;

import io.vertx.core.Future;

/**
 * The inbound side of a component for one endpoint. It owns the socket or poller, hands every
 * message to the handler it was built with and finishes its own protocol from the {@link Ack}
 * it gets back. Created by {@link Component#consumer}.
 *
 * @since 0.1
 */
public interface Consumer {

    Future<Void> start();

    Future<Void> stop();
}
