package dev.rail8.api;

import io.vertx.core.Future;
import io.vertx.core.Promise;

/**
 * The inbound side of a component for one endpoint. It owns the socket or poller, hands every
 * message to the handler it was built with and finishes its own protocol from the {@link Ack}
 * it gets back. Created by {@link Component#consumer}.
 *
 * <p>The node starts a consumer at most once and stops it once. When one does not start, or stops working
 * ({@link #broken()}), the node stops it and, after a delay that grows with each failure in a row, builds and starts a
 * fresh one of the same endpoint.
 *
 * @since 0.1
 */
public interface Consumer {

    /** Completes once the consumer takes messages; fails when it cannot. Called once. */
    Future<Void> start();

    /** Takes no new message, waits for the deliveries in flight and lets their answers reach the senders. Called once. */
    Future<Void> stop();

    /**
     * Fails, with the cause, once this consumer has stopped working on its own and takes no new message: its connection
     * dropped, its listener closed, or a callback of its own threw. A failed answer of the handler is that message's
     * failure, never a break. It fails at most once and never succeeds; it may fail on any thread.
     *
     * <p>The node reads it once {@link #start()} succeeded: a failure before that counts as a break right after the start.
     * Then it calls {@link #stop()}, which still waits for the deliveries in flight, and later starts a fresh consumer of the
     * same endpoint. A stop is not a break: it stays pending through {@link #stop()}. The node ignores a failure after it
     * called {@link #stop()}, and one of a consumer whose start failed.
     *
     * <p>The default never completes: it fits a consumer that cannot break after its start, such as a listener whose bind is
     * its start.
     *
     * @since 0.1
     */
    default Future<Void> broken() {
        return Promise.<Void>promise().future();
    }
}
