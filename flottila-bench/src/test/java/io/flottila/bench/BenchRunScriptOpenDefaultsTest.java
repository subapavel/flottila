package io.flottila.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * CORE-15, the implementer's cases on {@code bench/run.sh}'s {@code open} scenario (the architect's reading): BENCH_RATE
 * defaults to 5000 and is passed through as given — NodeBenchMain, not the script, refuses a bad rate — and an unset
 * BENCH_RAMP or BENCH_WINDOW leaves NodeBenchMain's own default. Runs the script as {@link BenchRunScriptOpenTest} does.
 */
class BenchRunScriptOpenDefaultsTest {

    @TempDir Path root;

    @Test
    void withoutBenchRateTheRunIsAtFiveThousandAndNodeBenchMainKeepsItsRampAndWindowDefaults() throws Exception {
        List<String> a = only(Map.of());
        assertEquals("5000", BenchRunScriptTest.last(a, "--rate"), a.toString());
        assertEquals("ack", BenchRunScriptTest.last(a, "--scenario"), a.toString());
        assertFalse(a.contains("--ramp"), "NodeBenchMain's PT1M: " + a);
        assertFalse(a.contains("--window"), "NodeBenchMain's PT10M: " + a);
    }

    @Test
    void benchRateIsPassedThroughAsGivenForNodeBenchMainToJudge() throws Exception {
        List<String> a = only(Map.of("BENCH_RATE", "fast"));
        assertEquals("fast", BenchRunScriptTest.last(a, "--rate"), "the script does not judge the rate: " + a);
    }

    /** The one NodeBenchMain run of BENCH_SCENARIOS=open with {@code env}; asserts exit 0. */
    List<String> only(Map<String, String> env) throws Exception {
        BenchRunScriptOpenTest script = new BenchRunScriptOpenTest();
        script.root = root;
        BenchRunScriptOpenTest.Result r = script.run(env);
        assertEquals(0, r.exit(), r.output());
        assertEquals(1, r.runs().size(), r.runs().toString());
        return r.runs().getFirst();
    }
}
