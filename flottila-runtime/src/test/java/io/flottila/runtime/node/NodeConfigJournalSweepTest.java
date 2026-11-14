package io.flottila.runtime.node;

import io.flottila.runtime.journal.SweepConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4d packet item 1 (CORE-3 items 10, 11 and 14): the sweeper's keys, read by NodeConfig (the only env reader), each with
 * its default, finite and range-checked, a refusal an {@link IllegalArgumentException} naming the key —
 * {@code FLOTTILA_JOURNAL_SWEEP_INTERVAL} (PT10S), {@code FLOTTILA_JOURNAL_SWEEP_GRACE} (PT1M),
 * {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE} (PT1H, PT1M..P1D).
 *
 * <p>The API assumed (CORE-4d, test-author): {@code NodeConfig.journalSweep()} answering
 * {@code io.flottila.runtime.journal.SweepConfig(Duration interval, Duration grace, Duration keepMaxAge)}. The ranges of the
 * interval (PT1S..PT1H) and the grace (PT1S..PT1H) are the test-author's proposal for the architect; KEEP_MAX_AGE's is CORE-3
 * item 14's.
 */
class NodeConfigJournalSweepTest {

    static Map<String, String> env() {
        return with(minimal(), "FLOTTILA_JOURNAL_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void theSweepKeysHaveTheirDefaults() {
        assertEquals(new SweepConfig(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofHours(1)), NodeConfig.from(env()).journalSweep());
    }

    @Test
    void theSweepKeysAreReadUnderTheirNamesAtTheirEdges() {
        assertEquals(Duration.ofSeconds(1), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_SWEEP_INTERVAL", "PT1S")).journalSweep().interval());
        assertEquals(Duration.ofHours(1), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_SWEEP_INTERVAL", "PT1H")).journalSweep().interval());
        assertEquals(Duration.ofSeconds(1), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_SWEEP_GRACE", "PT1S")).journalSweep().grace());
        assertEquals(Duration.ofHours(1), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_SWEEP_GRACE", "PT1H")).journalSweep().grace());
        assertEquals(Duration.ofMinutes(1), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_KEEP_MAX_AGE", "PT1M")).journalSweep().keepMaxAge());
        assertEquals(Duration.ofDays(1), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_KEEP_MAX_AGE", "P1D")).journalSweep().keepMaxAge());
        assertEquals(Duration.ofMinutes(90), NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_KEEP_MAX_AGE", "PT90M")).journalSweep().keepMaxAge(),
                "a value between the edges, as given");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "FLOTTILA_JOURNAL_SWEEP_INTERVAL, PT0S",
            "FLOTTILA_JOURNAL_SWEEP_INTERVAL, PT0.999S",
            "FLOTTILA_JOURNAL_SWEEP_INTERVAL, PT1H0.001S",
            "FLOTTILA_JOURNAL_SWEEP_INTERVAL, -PT10S",
            "FLOTTILA_JOURNAL_SWEEP_INTERVAL, 10",
            "FLOTTILA_JOURNAL_SWEEP_GRACE, PT0S",
            "FLOTTILA_JOURNAL_SWEEP_GRACE, PT0.999S",
            "FLOTTILA_JOURNAL_SWEEP_GRACE, PT1H0.001S",
            "FLOTTILA_JOURNAL_SWEEP_GRACE, soon",
            "FLOTTILA_JOURNAL_KEEP_MAX_AGE, PT59.999S",
            "FLOTTILA_JOURNAL_KEEP_MAX_AGE, P1DT0.001S",
            "FLOTTILA_JOURNAL_KEEP_MAX_AGE, P7D",
            "FLOTTILA_JOURNAL_KEEP_MAX_AGE, PT0S",
            "FLOTTILA_JOURNAL_KEEP_MAX_AGE, forever",
    })
    void aSweepKeyOutOfItsRangeIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }

    @Test
    void theSweepConfigRefusesOutOfRangeValuesItself() {
        assertThrows(IllegalArgumentException.class, () -> new SweepConfig(Duration.ZERO, Duration.ofMinutes(1), Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new SweepConfig(Duration.ofSeconds(10), Duration.ZERO, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new SweepConfig(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofDays(7)));
        assertThrows(NullPointerException.class, () -> new SweepConfig(null, Duration.ofMinutes(1), Duration.ofHours(1)));
    }
}
