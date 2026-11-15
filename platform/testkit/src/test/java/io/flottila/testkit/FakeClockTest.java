package io.flottila.testkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeClockTest {

    @Test
    void startsWhereToldAndMovesOnlyWhenAdvanced() {
        Instant t0 = Instant.parse("2026-10-18T10:00:00Z");
        FakeClock clock = FakeClock.at(t0);
        assertEquals(t0, clock.instant());
        assertEquals(t0, clock.instant(), "no wall-clock drift between two reads");
        clock.advance(Duration.ofMillis(1500));
        assertEquals(t0.plusMillis(1500), clock.instant());
        assertEquals(t0.plusMillis(1500).toEpochMilli(), clock.millis());
        assertEquals(ZoneOffset.UTC, clock.getZone());
    }

    @Test
    void setJumpsToAnInstantAndRefusesToGoBackwards() {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        clock.set(Instant.EPOCH.plusSeconds(60));
        assertEquals(Instant.EPOCH.plusSeconds(60), clock.instant());
        assertThrows(IllegalArgumentException.class, () -> clock.set(Instant.EPOCH.plusSeconds(59)),
                "deadlines and leases assume a monotonic test clock");
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofMillis(-1)));
    }

    @Test
    void withZoneKeepsTheSameTicking() {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        var berlin = clock.withZone(java.time.ZoneId.of("Europe/Berlin"));
        clock.advance(Duration.ofHours(1));
        assertEquals(Instant.EPOCH.plusSeconds(3600), berlin.instant(), "a re-zoned view ticks with the original");
        assertEquals(java.time.ZoneId.of("Europe/Berlin"), berlin.getZone());
    }
}
