package io.flottila.runtime.deploy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Optional;

/**
 * Listen addresses as {@code Component.listensOn} answers them — unresolved, the host as configured — compared without a bind or a
 * name lookup (D-072: one listener per host:port on a node).
 */
final class Listeners {

    private Listeners() {}

    /**
     * Whether {@code a} and {@code b} would share a listener: the same port, not 0 (an ephemeral port is never compared), and hosts
     * equal after normalising — an IP literal parsed without DNS ({@link InetAddress#ofLiteral}, brackets and every spelling of
     * one address alike), any other host a name compared lower-cased — or either host a wildcard: blank, or a literal that is
     * {@link InetAddress#isAnyLocalAddress() any-local} ({@code 0.0.0.0}, {@code ::}).
     */
    static boolean overlap(InetSocketAddress a, InetSocketAddress b) {
        if (a.getPort() == 0 || a.getPort() != b.getPort()) return false;
        Optional<String> x = host(a);
        Optional<String> y = host(b);
        return x.isEmpty() || y.isEmpty() || x.equals(y);
    }

    /** {@code host:port} as a problem line shows it: the host as configured, an IPv6 one in brackets (once). */
    static String shown(InetSocketAddress a) {
        String host = a.getHostString();
        return (host.contains(":") ? "[" + host.replace("[", "").replace("]", "") + "]" : host) + ":" + a.getPort();
    }

    /** The host of {@code a} as compared: empty for a wildcard, an IP literal in its canonical form, else the name lower-cased. */
    private static Optional<String> host(InetSocketAddress a) {
        String host = a.getHostString();
        if (host.isBlank()) return Optional.empty();
        try {
            InetAddress literal = InetAddress.ofLiteral(host);
            return literal.isAnyLocalAddress() ? Optional.empty() : Optional.of(literal.getHostAddress());
        } catch (IllegalArgumentException name) {   // no IP literal: a name, never resolved
            return Optional.of(host.toLowerCase(Locale.ROOT));
        }
    }
}
