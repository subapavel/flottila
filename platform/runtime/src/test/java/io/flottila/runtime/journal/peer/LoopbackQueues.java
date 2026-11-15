package io.flottila.runtime.journal.peer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The kernel's queues of one established loopback TCP socket, read from {@code /proc/net/tcp} and {@code /proc/net/tcp6}
 * (Linux; a JDK or Netty socket on 127.0.0.1 may be an IPv6 one with a mapped address): {@code rx}, the bytes that arrived
 * and its process has not read; {@code tx}, the bytes its process wrote that the other end has not acknowledged (on loopback:
 * not yet in the other end's receive queue). The witness that a process does not read a socket — {@code rx} above zero and
 * unchanged while that process's event loop runs tasks — and, with the bytes its sender wrote, how many it read: never a
 * timing (CORE-5a r2, test-author).
 */
public final class LoopbackQueues {

    /** One socket's queues, in bytes. */
    public record Queues(long tx, long rx) { }

    private LoopbackQueues() { }

    /** The queues of the established socket whose local port is {@code localPort} and whose peer's is {@code remotePort}. */
    public static Queues of(int localPort, int remotePort) throws IOException {
        return find(localPort, remotePort).orElseThrow(() ->
                new AssertionError("fixture: no established socket " + localPort + " -> " + remotePort + " in /proc/net/tcp or tcp6"));
    }

    /** {@link #of}, empty when no such socket is established (it closed). */
    public static Optional<Queues> find(int localPort, int remotePort) throws IOException {
        for (String file : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            for (String line : Files.readAllLines(p)) {
                String[] f = line.trim().split("\\s+");
                // sl local_address rem_address st tx_queue:rx_queue ...; the header's address columns have no colon
                if (f.length < 5 || !f[1].contains(":") || !f[3].equals("01")) continue;   // 01: ESTABLISHED
                if (port(f[1]) == localPort && port(f[2]) == remotePort) {
                    String[] q = f[4].split(":");
                    return Optional.of(new Queues(Long.parseLong(q[0], 16), Long.parseLong(q[1], 16)));
                }
            }
        }
        return Optional.empty();
    }

    private static int port(String hexAddress) {
        return Integer.parseInt(hexAddress.substring(hexAddress.lastIndexOf(':') + 1), 16);
    }
}
