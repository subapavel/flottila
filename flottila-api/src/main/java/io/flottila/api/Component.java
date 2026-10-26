package io.flottila.api;

import io.vertx.core.Future;
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
}
