package io.flottila.api;

import io.vertx.core.Future;

/**
 * The outbound side of a component for one endpoint: sends an envelope and yields the envelope
 * that came back (the reply, or the sent envelope with transport headers such as {@code http.status}).
 * Producers pipe {@link Body#open()}; they never buffer. A producer sends the message id to its partner under
 * {@link Envelope#H_MESSAGE_ID} ({@link Envelope#withOutboundId()}), so the partner can dedupe a rerun (D-101 (3)).
 *
 * @since 0.1
 */
public interface Producer {

    Future<Envelope> send(Envelope e);

    /**
     * {@link #send(Envelope)} bounded by {@code d} (D-113 draft): with no time left ({@code d.remaining()} zero or less) a
     * failed future with a retryable {@link NackException}, nothing started; else {@code send(e)}. A producer that overrides
     * it also cuts a send still open once a re-read of {@code d} finds no time left — the same retryable failure.
     */
    default Future<Envelope> send(Envelope e, Deadline d) {
        if (!d.remaining().isPositive()) {
            return Future.failedFuture(new NackException("deadline passed: message " + e.id() + " not sent", true));
        }
        return send(e);
    }

    Future<Void> close();
}
