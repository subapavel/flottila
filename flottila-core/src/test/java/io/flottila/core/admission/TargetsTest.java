package io.flottila.core.admission;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targets (D-099): one {@link TargetLimit} per target key for the lifetime of the Targets, with the override for its key or the
 * defaults; an unshared limit per component without a target, never listed; the gauges sorted by target.
 */
class TargetsTest {

    static final String A = "https://a.example:443";
    static final String B = "http://b.example:80";
    static final String Z = "sftp://z.example:22";

    final FakeTimer timer = new FakeTimer(FakeClock.at(Instant.parse("2026-01-01T00:00:00Z")));
    final Targets.Limit defaults = new Targets.Limit(3, Duration.ofSeconds(2));

    @Test
    void ofAnswersOneLimitPerTargetWithItsOverride() {
        Targets targets = new Targets(defaults, Map.of(B, new Targets.Limit(1, Duration.ofSeconds(7))), timer);
        TargetLimit a = targets.of(A);
        assertSame(a, targets.of(A), "one limit per key: two flows naming A share it");
        TargetLimit b = targets.of(B);
        assertNotSame(a, b, "another key, another limit");

        assertEquals(3, a.gauge().maxCalls(), "no override for A: the defaults");
        assertEquals(Duration.ofSeconds(2), a.gauge().maxWait());
        assertEquals(1, b.gauge().maxCalls(), "B's override");
        assertEquals(Duration.ofSeconds(7), b.gauge().maxWait());

        assertTrue(b.acquire().succeeded(), "B's one permit is granted at once");
        assertFalse(targets.of(B).acquire().isComplete(), "the second acquire on B, through of(B) again, waits: the same one-permit limit");
        var permit = a.acquire().result();
        permit.close();
        assertSame(a, targets.of(A), "a key's limit lives as long as the Targets, idle or not");
    }

    @Test
    void unsharedIsAFreshDefaultLimitNotListed() {
        Targets targets = new Targets(defaults, Map.of(), timer);
        TargetLimit u1 = targets.unshared();
        TargetLimit u2 = targets.unshared();
        assertNotSame(u1, u2, "every component without a target gets a limit of its own");
        assertEquals(3, u1.gauge().maxCalls(), "the defaults: finite, never unlimited");
        assertEquals(Duration.ofSeconds(2), u1.gauge().maxWait());

        for (int i = 0; i < 3; i++) assertTrue(u1.acquire().succeeded(), "u1 permit " + (i + 1) + " of 3");
        assertFalse(u1.acquire().isComplete(), "u1's fourth waits: the defaults bound it");
        assertTrue(u2.acquire().succeeded(), "u2 is not u1: its first permit is free");

        targets.of(A);
        assertEquals(List.of(A), targets.gauges().stream().map(g -> g.target()).toList(), "only keyed limits are listed");
    }

    @Test
    void gaugesAreSortedByTarget() {
        Targets targets = new Targets(defaults, Map.of(B, new Targets.Limit(1, Duration.ofSeconds(7))), timer);
        targets.of(Z);
        targets.of(B);
        targets.of(A);
        assertTrue(targets.of(B).acquire().succeeded());
        var gauges = targets.gauges();
        assertEquals(List.of(B, A, Z), gauges.stream().map(g -> g.target()).toList(), "sorted by target (http < https < sftp)");
        var b = gauges.get(0);
        assertEquals(1, b.maxCalls());
        assertEquals(Duration.ofSeconds(7), b.maxWait());
        assertEquals(1, b.inFlight());
        assertEquals(0, b.waiting());
        assertEquals(0, b.refused());
        assertEquals(0, gauges.get(1).inFlight());
    }

    /** Refused at construction or at the latest when the limit is made: the lambda covers both. */
    @Test
    void nonPositiveDefaultsOrOverridesRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Targets(new Targets.Limit(0, Duration.ofSeconds(1)), Map.of(), timer).unshared(), "defaults maxCalls 0");
        assertThrows(IllegalArgumentException.class, () -> new Targets(new Targets.Limit(1, Duration.ZERO), Map.of(), timer).unshared(), "defaults maxWait 0");
        assertThrows(IllegalArgumentException.class, () -> new Targets(defaults, Map.of(A, new Targets.Limit(-1, Duration.ofSeconds(1))), timer).of(A), "override maxCalls -1");
        assertThrows(IllegalArgumentException.class, () -> new Targets(defaults, Map.of(A, new Targets.Limit(1, Duration.ofMillis(-5))), timer).of(A), "override maxWait negative");
    }

    /** D-099, architect 2026-11-03: a node without its own setting limits a target to 16 calls in flight and a 5 s wait. */
    @Test
    void defaultsAreSixteenCallsAndFiveSeconds() {
        assertEquals(new Targets.Limit(16, Duration.ofSeconds(5)), Targets.DEFAULTS);
    }
}
