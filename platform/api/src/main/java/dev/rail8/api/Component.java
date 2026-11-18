package dev.rail8.api;

import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.Function;

/**
 * The only way the platform touches the outside world. One implementation per scheme: the node ships some and a
 * {@link dev.rail8.api.plugin.Plugin} adds others, each built from the node's
 * {@link dev.rail8.api.plugin.ComponentContext}. Every component ships with its mock and
 * both pass the same contract suite.
 *
 * @since 0.1
 */
public interface Component {

    /** {@code "http"}, {@code "sftp"}, {@code "mock"} … */
    String scheme();

    /**
     * What an endpoint of this scheme may set in its {@code options}: the node checks every endpoint file against it when
     * it validates, deploys or restores a package, the admin API serves it and the UI draws its form from it. The node
     * calls it each time it does one of these, so it returns the same description on every call. A component that
     * describes nothing returns {@link Settings#NONE}, the default, and is checked against {@link #configSchema()}
     * instead. A plugin's component is also called once when the node loads the plugin; a throw there refuses the
     * node's start.
     *
     * @since 0.1
     */
    default Settings settings() {
        return Settings.NONE;
    }

    /**
     * JSON Schema (as text) the endpoint options are validated against at deploy time, consulted only while
     * {@link #settings()} describes nothing. The default accepts any object.
     */
    default String configSchema() {
        return "{\"type\":\"object\"}";
    }

    /**
     * A consumer that calls {@code handler} for every message and completes its protocol from the returned {@link Ack}. The node
     * may call it again for {@code cfg}, with the same handler, once the previous consumer of {@code cfg} stopped: after its start
     * failed, after it broke ({@link Consumer#broken()}), or after its poller lease moved. It starts each consumer at most once:
     * a stopped consumer is never started again.
     */
    Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler);

    Producer producer(EndpointConfig cfg);

    Future<Health> health();

    /**
     * The local host and port a consumer of {@code cfg} binds, if it accepts connections; empty if it polls, subscribes or
     * connects out. Answered from {@code cfg} alone — already checked against {@link #settings()} — as an unresolved address
     * ({@link InetSocketAddress#createUnresolved}, the host as configured): nothing is bound and no name is resolved. Port 0
     * (any free port) is never compared. The node calls it at deploy and at restore, on a virtual thread, to refuse two
     * listeners on one host:port; a throw is a problem line of that deploy.
     *
     * @since 0.1
     */
    default Optional<InetSocketAddress> listensOn(EndpointConfig cfg) {
        return Optional.empty();
    }

    /**
     * Whether a consumer of {@code cfg} polls what another node may poll as well (a remote directory, a mailbox): answered from
     * {@code cfg} alone — already checked against {@link #settings()} — nothing is opened. When true, the node runs a consumer
     * of {@code cfg} only while it holds the endpoint's lease, and the next consumer of {@code cfg} starts only once the one
     * before it stopped. The node calls it at deploy and at restore, on a virtual thread; a throw is a problem line of that
     * deploy.
     *
     * @since 0.1
     */
    default boolean polls(EndpointConfig cfg) {
        return false;
    }

    /**
     * The target system a producer of {@code cfg} calls, the key its sends are limited by: {@code scheme://host:port},
     * lower-cased, the scheme's default port filled in, never userinfo, path, query or fragment — two endpoints on paths of one
     * partner name one target, and flows naming it share its limit. Empty: the component names none, and each {@code to} on it
     * gets a finite limit of its own. Answered from {@code cfg} alone — already checked against {@link #settings()} — nothing
     * is opened and no name is resolved. The node calls it when it builds a flow's {@code to}, after {@link #producer}; a throw
     * fails that build.
     *
     * @since 0.1
     */
    default Optional<String> target(EndpointConfig cfg) {
        return Optional.empty();
    }
}
