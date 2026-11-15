package io.flottila.bench;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15: the open loop's options. {@code --rate} (1..100 000 msg/s) switches an ack or sync run to the open loop (and
 * {@code --scenario echo} is the harness' self-test); {@code --ramp} (PT0S..PT10M, default PT1M), {@code --window} (PT1S..PT1H,
 * default PT10M), {@code --max-outstanding} (a positive number, default rate × 2 s) and {@code --pg-cpu-source} (unset by
 * default) shape it. Without {@code --rate} the run is the closed loop as before: {@code open()} is null.
 */
class NodeBenchMainOpenOptionsTest {

    @Test
    void aRateAloneTakesTheDefaultRampWindowAndBoundOfTwoSecondsAtTheRate() {
        NodeBenchMain.Open open = NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "5000"}).open();
        assertNotNull(open, "--rate makes the run open loop");
        assertEquals(5000, open.rate());
        assertEquals(Duration.ofMinutes(1), open.ramp(), "the ramp defaults to PT1M");
        assertEquals(Duration.ofMinutes(10), open.window(), "the window defaults to PT10M");
        assertEquals(10_000, open.maxOutstanding(), "rate × 2 s");
        assertNull(open.pgCpuSource(), "no Postgres CPU source unless named");

        assertEquals(2, NodeBenchMain.options(new String[] {"--scenario", "sync", "--rate", "1"}).open().maxOutstanding(), "1/s × 2 s");
        assertEquals(200_000, NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "100000"}).open().maxOutstanding(),
                "100 000/s × 2 s");
    }

    @Test
    void everyOpenLoopOptionIsTakenAtItsBounds() {
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "sync", "--rate", "100000", "--ramp", "PT10M",
                "--window", "PT1H", "--max-outstanding", "7", "--pg-cpu-source", "/sys/fs/cgroup/system.slice/postgresql.service"});
        assertEquals(NodeBenchMain.Scenario.SYNC, o.scenario());
        assertEquals(100_000, o.open().rate(), "the highest rate taken");
        assertEquals(Duration.ofMinutes(10), o.open().ramp(), "the longest ramp taken");
        assertEquals(Duration.ofHours(1), o.open().window(), "the longest window taken");
        assertEquals(7, o.open().maxOutstanding(), "an explicit bound replaces rate × 2 s");
        assertEquals("/sys/fs/cgroup/system.slice/postgresql.service", o.open().pgCpuSource());

        NodeBenchMain.Open low = NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "1", "--ramp", "PT0S", "--window",
                "PT1S", "--max-outstanding", "1"}).open();
        assertEquals(1, low.rate(), "the lowest rate taken");
        assertEquals(Duration.ZERO, low.ramp(), "no ramp is taken");
        assertEquals(Duration.ofSeconds(1), low.window(), "the shortest window taken");
        assertEquals(1, low.maxOutstanding());
    }

    @Test
    void theEchoScenarioIsTheHarnesssOwnCeilingWithARate() {
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "echo", "--rate", "2000", "--ramp", "PT1S", "--window", "PT5S"});
        assertEquals(NodeBenchMain.Scenario.ECHO, o.scenario());
        assertEquals(2000, o.open().rate());
        assertEquals(Duration.ofSeconds(1), o.open().ramp());
        assertEquals(Duration.ofSeconds(5), o.open().window());
    }

    @Test
    void withoutARateTheRunIsTheClosedLoopAsBefore() {
        for (String scenario : new String[] {"sync", "ack", "worker"}) {
            NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", scenario, "--concurrency", "8"});
            assertNull(o.open(), scenario + " without --rate is closed loop");
            assertEquals(8, o.concurrency());
            assertEquals(NodeBenchMain.defaultInFlight(o.scenario(), 8), o.maxInFlight(), "the closed loop's flow limit is unchanged");
        }
    }

    @Test
    void aRateOutsideOneToOneHundredThousandOrNotANumberIsRefusedNamingTheOptionAndTheValue() {
        for (String bad : new String[] {"0", "-1", "100001", "fast", "2.5", ""}) refused("--rate", "--scenario", "ack", "--rate", bad);
    }

    @Test
    void aRampOutsideZeroToTenMinutesOrNotAnIsoDurationIsRefusedNamingTheOptionAndTheValue() {
        for (String bad : new String[] {"PT10M0.001S", "PT-1S", "PT11M", "1m", "60"}) {
            refused("--ramp", "--scenario", "ack", "--rate", "100", "--ramp", bad);
        }
    }

    @Test
    void aWindowOutsideOneSecondToOneHourOrNotAnIsoDurationIsRefusedNamingTheOptionAndTheValue() {
        for (String bad : new String[] {"PT0S", "PT0.999S", "PT1H0.001S", "PT-5S", "P1D", "ten"}) {
            refused("--window", "--scenario", "ack", "--rate", "100", "--window", bad);
        }
    }

    @Test
    void aMaxOutstandingThatIsNotAPositiveNumberIsRefusedNamingTheOptionAndTheValue() {
        for (String bad : new String[] {"0", "-3", "many", "1.5"}) {
            refused("--max-outstanding", "--scenario", "ack", "--rate", "100", "--max-outstanding", bad);
        }
    }

    /** {@code args} are refused with an IllegalArgumentException naming {@code option} and the value after it. */
    static void refused(String option, String... args) {
        String value = args[args.length - 1];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeBenchMain.options(args), option + " " + value);
        assertTrue(e.getMessage() != null && e.getMessage().contains(option), "names the option " + option + ": " + e.getMessage());
        assertTrue(e.getMessage().contains("'" + value + "'"), "names the value '" + value + "': " + e.getMessage());
    }
}
