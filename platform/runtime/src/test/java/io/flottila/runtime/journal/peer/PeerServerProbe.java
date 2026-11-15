package io.flottila.runtime.journal.peer;

/**
 * Reads what a {@link PeerServer} keeps per source address, for the tests outside its package (CORE-5a2 review r1, M14): the
 * number of addresses with a place taken among their {@value PeerConfig#HANDSHAKES_PER_SOURCE}. An address whose last place
 * is given back must leave nothing behind, or the server keeps one entry per address ever seen.
 *
 * <p>The API assumed (CORE-5a2 r1, test-author): package-private {@code int PeerServer.sourcesHeld()}, safe to call from any
 * thread.
 */
public final class PeerServerProbe {

    private PeerServerProbe() {
    }

    /** The number of source addresses {@code server} holds a place for. */
    public static int sourcesHeld(PeerServer server) {
        return server.sourcesHeld();
    }
}
