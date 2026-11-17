package dev.rail8.runtime.journal.peer;

import java.time.Duration;
import java.util.Objects;

/**
 * The peer link's settings, read by {@code NodeConfig} from the {@code RAIL8_PEER_*} keys; an in-process one is
 * held to the same ranges, each refusal naming its key.
 *
 * @param bind    {@code RAIL8_PEER_BIND}: the address the link listens on (0.0.0.0)
 * @param port    {@code RAIL8_PEER_PORT}: its port, 0 to 65535, 0 any free one (8083)
 * @param host    {@code RAIL8_PEER_HOST}: the host the other nodes dial, written with the port into the node's row (the node id)
 * @param timeout {@code RAIL8_PEER_TIMEOUT}: PT0.1S to PT30S, how long a link may go without the answer it waits for (PT2S)
 * @param reads   {@code RAIL8_PEER_READS}: 1 to 64, the reads in flight to one peer; a read above it waits (16)
 * @since 0.1
 */
public record PeerConfig(String bind, int port, String host, Duration timeout, int reads) {

    /** {@code RAIL8_PEER_PORT}'s default. */
    public static final int PORT = 8083;
    /** {@code RAIL8_PEER_TIMEOUT}'s default. */
    public static final Duration TIMEOUT = Duration.ofSeconds(2);
    /** {@code RAIL8_PEER_READS}'s default. */
    public static final int READS = 16;
    /** The shortest {@code RAIL8_PEER_TIMEOUT}. */
    public static final Duration TIMEOUT_MIN = Duration.ofMillis(100);
    /** The longest {@code RAIL8_PEER_TIMEOUT}. */
    public static final Duration TIMEOUT_MAX = Duration.ofSeconds(30);
    /** The most {@code RAIL8_PEER_READS}. */
    public static final int READS_MAX = 64;
    /** The most connections a listener holds in their handshake (not yet proven); a further one is closed at once. */
    public static final int HANDSHAKES_MAX = 64;
    /** The most of them from one source address, beside {@link #HANDSHAKES_MAX}; a further one is closed at once. */
    public static final int HANDSHAKES_PER_SOURCE = 8;

    public PeerConfig {
        if (Objects.requireNonNull(bind, "bind").isBlank()) throw new IllegalArgumentException("RAIL8_PEER_BIND must not be blank");
        if (Objects.requireNonNull(host, "host").isBlank()) throw new IllegalArgumentException("RAIL8_PEER_HOST must not be blank");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("RAIL8_PEER_PORT must be between 0 and 65535, was " + port);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(TIMEOUT_MIN) < 0 || timeout.compareTo(TIMEOUT_MAX) > 0) {
            throw new IllegalArgumentException("RAIL8_PEER_TIMEOUT must be from " + TIMEOUT_MIN + " to " + TIMEOUT_MAX + ", was " + timeout);
        }
        if (reads < 1 || reads > READS_MAX) throw new IllegalArgumentException("RAIL8_PEER_READS must be between 1 and " + READS_MAX + ", was " + reads);
    }

    /**
     * The address the other nodes dial for a link listening on {@code actualPort}: {@code host:port}. The dialler splits it at
     * the last colon and drops brackets, so an IPv6 literal is dialled with or without them.
     */
    public String address(int actualPort) {
        return host + ":" + actualPort;
    }
}
