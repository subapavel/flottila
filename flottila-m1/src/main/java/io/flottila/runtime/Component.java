package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.net.URI;

/** Component SPI — reactive on both ends. */
public interface Component {
    String scheme();
    Consumer consumer(URI endpoint, String flow);
    /** opts come straight from the flow config step (e.g. forwardHeaders allow-list). */
    Producer producer(URI endpoint, JsonObject opts);

    /** How ingress persists an envelope before an irreversible ACK. Injected at build time. */
    @FunctionalInterface
    interface DurableIngress {
        Uni<Void> persist(Envelope e);
    }

    interface Consumer {
        /** Ingress stream; backpressure via request(n). Subscribed once per flow deployment. */
        Multi<Envelope> stream();
        /** Called with the pipeline's final envelope for request/reply transports; no-op otherwise. */
        default void reply(Envelope result) {}
        default void fail(Envelope original, Throwable t) {}
        default Uni<Void> start() { return Uni.createFrom().voidItem(); }
        /**
         * Injected at build time. A consumer whose ACK is irreversible awaits this before
         * acknowledging (timeout → NACK); request/reply consumers ignore it — the client owns
         * the retry until it gets the reply. When queued is true, persist() also enqueued the
         * message for a worker — the consumer must NOT emit it into its own stream.
         */
        default void durableIngress(DurableIngress d, boolean queued) {}
    }

    interface Producer {
        Uni<Envelope> send(Envelope e);
    }
}
