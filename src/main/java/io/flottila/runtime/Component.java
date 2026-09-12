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

    interface Consumer {
        /** Ingress stream; backpressure via request(n). Subscribed once per flow deployment. */
        Multi<Envelope> stream();
        /** Called with the pipeline's final envelope for request/reply transports; no-op otherwise. */
        default void reply(Envelope result) {}
        default void fail(Envelope original, Throwable t) {}
        default Uni<Void> start() { return Uni.createFrom().voidItem(); }
    }

    interface Producer {
        Uni<Envelope> send(Envelope e);
    }
}
