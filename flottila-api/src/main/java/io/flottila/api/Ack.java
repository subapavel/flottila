package io.flottila.api;

import java.util.Objects;

/**
 * What a consumer's handler returns ({@code Function<Envelope, Future<Ack>>}): the state the
 * consumer needs to finish its own protocol — delete the file, commit the session, send 202 or the
 * reply itself. The durable save before an irreversible ACK is the consumer's job (ARCHITECTURE §4, §7).
 * Draft — review at hand-over.
 *
 * @since 0.1
 */
public sealed interface Ack permits Ack.Accepted, Ack.Reply, Ack.Nack {

    /**
     * Accepted for processing (async modes): the message is durable, the consumer may ACK irreversibly.
     *
     * @since 0.1
     */
    record Accepted(String messageId) implements Ack {
        public Accepted { Objects.requireNonNull(messageId, "messageId"); }
    }

    /**
     * Sync request/reply: the reply is the acknowledgement.
     *
     * @since 0.1
     */
    record Reply(Envelope reply) implements Ack {
        public Reply { Objects.requireNonNull(reply, "reply"); }
    }

    /**
     * Not accepted: {@code retryable} says whether the sender may try again (503 + Retry-After) or not (4xx).
     *
     * @since 0.1
     */
    record Nack(String reason, boolean retryable) implements Ack {
        public Nack { Objects.requireNonNull(reason, "reason"); }
    }
}
