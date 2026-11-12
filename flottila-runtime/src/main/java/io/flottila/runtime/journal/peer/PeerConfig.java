package io.flottila.runtime.journal.peer;

import java.time.Duration;
import java.util.Objects;

/**
 * The peer link's settings (CORE-5 S11), read by {@code NodeConfig} from the {@code FLOTTILA_PEER_*} keys; an in-process one is
 * held to the same ranges, each refusal naming its key.
 *
 * @param bind    {@code FLOTTILA_PEER_BIND}: the address the link listens on (0.0.0.0)
 * @param port    {@code FLOTTILA_PEER_PORT}: its port, 0 to 65535, 0 any free one (8083)
 * @param host    {@code FLOTTILA_PEER_HOST}: the host the other nodes dial, written with the port into the node's row (the node id)
 * @param timeout {@code FLOTTILA_PEER_TIMEOUT}: PT0.1S to PT30S, how long a link may go without the answer it waits for (PT2S)
 * @param reads   {@code FLOTTILA_PEER_READS}: 1 to 256, the reads in flight to one peer; a read above it waits (16)
 * @since 0.1
 */
public record PeerConfig(String bind, int port, String host, Duration timeout, int reads) {

    /** {@code FLOTTILA_PEER_PORT}'s default. */
    public static final int PORT = 8083;
    /** {@code FLOTTILA_PEER_TIMEOUT}'s default. */
    public static final Duration TIMEOUT = Duration.ofSeconds(2);
    /** {@code FLOTTILA_PEER_READS}'s default. */
    public static final int READS = 16;
    /** The shortest {@code FLOTTILA_PEER_TIMEOUT}. */
    public static final Duration TIMEOUT_MIN = Duration.ofMillis(100);
    /** The longest {@code FLOTTILA_PEER_TIMEOUT}. */
    public static final Duration TIMEOUT_MAX = Duration.ofSeconds(30);
    /** The most {@code FLOTTILA_PEER_READS}. */
    public static final int READS_MAX = 256;
    /** The most connections a listener holds in their handshake (not yet proven); a further one is closed at once (r2 R1). */
    public static final int HANDSHAKES_MAX = 64;

    public PeerConfig {
        if (Objects.requireNonNull(bind, "bind").isBlank()) throw new IllegalArgumentException("FLOTTILA_PEER_BIND must not be blank");
        if (Objects.requireNonNull(host, "host").isBlank()) throw new IllegalArgumentException("FLOTTILA_PEER_HOST must not be blank");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("FLOTTILA_PEER_PORT must be between 0 and 65535, was " + port);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(TIMEOUT_MIN) < 0 || timeout.compareTo(TIMEOUT_MAX) > 0) {
            throw new IllegalArgumentException("FLOTTILA_PEER_TIMEOUT must be from " + TIMEOUT_MIN + " to " + TIMEOUT_MAX + ", was " + timeout);
        }
        if (reads < 1 || reads > READS_MAX) throw new IllegalArgumentException("FLOTTILA_PEER_READS must be between 1 and " + READS_MAX + ", was " + reads);
    }

    /**
     * The address the other nodes dial for a link listening on {@code actualPort}: {@code host:port}. The dialler splits it at
     * the last colon and drops brackets, so an IPv6 literal is dialled with or without them.
     */
    public String address(int actualPort) {
        return host + ":" + actualPort;
    }
}
