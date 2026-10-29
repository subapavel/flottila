package io.flottila.api;

import io.vertx.core.Future;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.Function;

/**
 * The only way the platform touches the outside world (ARCHITECTURE §7). One implementation per
 * scheme; the node's {@code Main} wires the shipped components explicitly, because a component needs
 * the node's Vert.x, which api may not name (D-057, F-4). Every component ships with its mock and
 * both pass the same contract suite.
 *
 * @since 0.1
 */
public interface Component {

    /** {@code "http"}, {@code "sftp"}, {@code "mock"} … */
    String scheme();

    /** JSON Schema (as text) the endpoint options are validated against at deploy time. */
    String configSchema();

    /** A consumer that calls {@code handler} for every message and completes its protocol from the returned {@link Ack}. */
    Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler);

    Producer producer(EndpointConfig cfg);

    Future<Health> health();

    /**
     * The local host and port a consumer of {@code cfg} binds, if it accepts connections; empty if it polls, subscribes or
     * connects out. Answered from {@code cfg} alone — already valid against {@link #configSchema()} — as an unresolved address
     * ({@link InetSocketAddress#createUnresolved}, the host as configured): nothing is bound and no name is resolved. Port 0
     * (any free port) is never compared. The node calls it at deploy and at restore, on a virtual thread, to refuse two
     * listeners on one host:port (D-072); a throw is a problem line of that deploy.
     *
     * @since 0.1
     */
    default Optional<InetSocketAddress> listensOn(EndpointConfig cfg) {
        return Optional.empty();
    }

    /**
     * Whether a consumer of {@code cfg} polls what another node may poll as well (a remote directory, a mailbox): answered from
     * {@code cfg} alone — already valid against {@link #configSchema()} — nothing is opened. When true, the node runs a consumer
     * of {@code cfg} only while it holds the endpoint's lease (D-083), so {@link #consumer} may be called again for {@code cfg}
     * after the previous consumer stopped: a stopped consumer is never started again. The node calls it at deploy and at
     * restore, on a virtual thread; a throw is a problem line of that deploy.
     *
     * @since 0.1
     */
    default boolean polls(EndpointConfig cfg) {
        return false;
    }
}
