package io.flottila.runtime.deploy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09g (D-072): {@code Listeners.overlap(a, b)} — two listen addresses overlap when they have the same port, that port is not 0
 * (an ephemeral port is never compared), and their hosts are equal after normalising (brackets stripped; an IP literal parsed
 * without DNS; any other host a name, compared lower-cased) or either host is a wildcard ({@code 0.0.0.0}, {@code ::} in any
 * spelling, or blank). The addresses are unresolved, as {@code Component.listensOn} answers them.
 */
class ListenersTest {

    static InetSocketAddress at(String host, int port) {
        return InetSocketAddress.createUnresolved(host, port);
    }

    static Arguments row(String why, InetSocketAddress a, InetSocketAddress b, boolean overlap) {
        return Arguments.of(why, a, b, overlap);
    }

    static Stream<Arguments> overlapTable() {
        return Stream.of(
                row("the same host and port", at("127.0.0.1", 8080), at("127.0.0.1", 8080), true),
                row("0.0.0.0 against an IPv4 host", at("0.0.0.0", 8080), at("10.1.2.3", 8080), true),
                row("0.0.0.0 against an IPv6 host", at("0.0.0.0", 8080), at("::1", 8080), true),
                row("0.0.0.0 against a name", at("0.0.0.0", 8080), at("orders.example", 8080), true),
                row(":: against an IPv4 host", at("::", 8080), at("127.0.0.1", 8080), true),
                row("[::] against a name", at("[::]", 8080), at("localhost", 8080), true),
                row("0:0:0:0:0:0:0:0 is ::", at("0:0:0:0:0:0:0:0", 8080), at("192.168.1.1", 8080), true),
                row("a blank host is a wildcard", at("", 8080), at("127.0.0.1", 8080), true),
                row("a host of spaces is blank", at("  ", 8080), at("orders.example", 8080), true),
                row("127.0.0.1 against 127.0.0.2", at("127.0.0.1", 8080), at("127.0.0.2", 8080), false),
                row("127.0.0.1 against ::1", at("127.0.0.1", 8080), at("::1", 8080), false),
                row("::1 against [::1]", at("::1", 8080), at("[::1]", 8080), true),
                row("::1 against 0:0:0:0:0:0:0:1", at("::1", 8080), at("0:0:0:0:0:0:0:1", 8080), true),
                row("a name in another case", at("Orders.Example", 8080), at("orders.example", 8080), true),
                row("two names", at("orders.example", 8080), at("billing.example", 8080), false),
                row("a name is never resolved: localhost is not 127.0.0.1", at("localhost", 8080), at("127.0.0.1", 8080), false),
                row("port 0 on the same host", at("127.0.0.1", 0), at("127.0.0.1", 0), false),
                row("port 0 on the wildcard", at("0.0.0.0", 0), at("127.0.0.1", 0), false),
                row("different ports", at("127.0.0.1", 8080), at("127.0.0.1", 8081), false),
                row("the wildcard on another port", at("0.0.0.0", 8080), at("127.0.0.1", 8081), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overlapTable")
    void overlapTable(String why, InetSocketAddress a, InetSocketAddress b, boolean overlap) {
        assertEquals(overlap, Listeners.overlap(a, b), why + ": " + a + " / " + b);
        assertEquals(overlap, Listeners.overlap(b, a), why + ", the other way round: " + b + " / " + a);
    }

    /** Hosts of every kind the rule tells apart: IPv4 and IPv6 literals in several spellings, wildcards, blanks, names in any case. */
    @Provide
    Arbitrary<InetSocketAddress> addresses() {
        Arbitrary<String> literals = Arbitraries.of("127.0.0.1", "127.0.0.2", "10.0.0.1", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", "::1", "[::1]",
                "0:0:0:0:0:0:0:1", "", " ");
        Arbitrary<String> names = Combinators.combine(Arbitraries.of("localhost", "orders.example", "billing.example"), Arbitraries.longs())
                .as(ListenersTest::recased);
        Arbitrary<String> hosts = Arbitraries.oneOf(literals, names);
        return Combinators.combine(hosts, Arbitraries.of(0, 8080, 8081)).as(InetSocketAddress::createUnresolved);
    }

    /** {@code name} with the letters whose bit in {@code mask} is set upper-cased. */
    static String recased(String name, long mask) {
        StringBuilder s = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            String c = name.substring(i, i + 1);
            s.append((mask >>> (i % 64) & 1) == 1 ? c.toUpperCase(Locale.ROOT) : c);
        }
        return s.toString();
    }

    /** Symmetric; never across ports nor on port 0; and an address on a real port overlaps itself. */
    @Property(tries = 1000)
    void overlapIsSymmetricAndPortZeroNeverOverlaps(@ForAll("addresses") InetSocketAddress a, @ForAll("addresses") InetSocketAddress b) {
        boolean ab = Listeners.overlap(a, b);
        assertEquals(ab, Listeners.overlap(b, a), "symmetric: " + a + " / " + b);
        if (a.getPort() == 0 || b.getPort() == 0) assertFalse(ab, "port 0 is never compared: " + a + " / " + b);
        if (a.getPort() != b.getPort()) assertFalse(ab, "different ports never overlap: " + a + " / " + b);
        if (a.getPort() != 0) assertTrue(Listeners.overlap(a, a), "an address on a real port overlaps itself: " + a);
    }
}
