package io.flottila.api;

import io.vertx.core.Future;

/**
 * The outbound side of a component for one endpoint: sends an envelope and yields the envelope
 * that came back (the reply, or the sent envelope with transport headers such as {@code http.status}).
 * Producers pipe {@link Body#open()}; they never buffer.
 *
 * @since 0.1
 */
public interface Producer {

    Future<Envelope> send(Envelope e);

    Future<Void> close();
}
