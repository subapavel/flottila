package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-124, the implementer's cases on {@code bench/run.sh}'s {@code open} scenario: BENCH_RECORDING_LEVEL is passed as
 * {@code --recording-level} and BENCH_MAX_IN_FLIGHT as {@code --max-in-flight}, each as given and only when set (NodeBenchMain
 * judges the value); a set level is in the run's name, so an all and a none run never write the same file; the summary
 * prints the record's recording level and whether it settled. Runs a copy of the script with the stubs of
 * {@link BenchRunScriptOpenTest}, the java stub writing {@code record} to the run's {@code --out}.
 */
class BenchRunScriptOpenLevelTest {

    @TempDir Path root;
    final AtomicInteger runs = new AtomicInteger();

    record Result(int exit, String output, List<List<String>> runs) {}

    @Test
    void benchRecordingLevelAndMaxInFlightArePassedThroughWhenSet() throws Exception {
        List<String> a = only(Map.of("BENCH_RECORDING_LEVEL", "none", "BENCH_MAX_IN_FLIGHT", "2048"));
        assertEquals("none", BenchRunScriptTest.last(a, "--recording-level"), a.toString());
        assertEquals("2048", BenchRunScriptTest.last(a, "--max-in-flight"), a.toString());
    }

    @Test
    void eachIsPassedWithoutTheOther() throws Exception {
        List<String> level = only(Map.of("BENCH_RECORDING_LEVEL", "sometimes"));
        assertEquals("sometimes", BenchRunScriptTest.last(level, "--recording-level"), "as given, for NodeBenchMain to judge: " + level);
        assertFalse(level.contains("--max-in-flight"), level.toString());

        List<String> limit = only(Map.of("BENCH_MAX_IN_FLIGHT", "64"));
        assertEquals("64", BenchRunScriptTest.last(limit, "--max-in-flight"), limit.toString());
        assertFalse(limit.contains("--recording-level"), limit.toString());
    }

    @Test
    void unsetTheyLeaveNodeBenchMainItsDefaults() throws Exception {
        List<String> a = only(Map.of());
        assertFalse(a.contains("--recording-level"), "NodeBenchMain's all: " + a);
        assertFalse(a.contains("--max-in-flight"), "NodeBenchMain's default limit: " + a);
    }

    @Test
    void aSetLevelIsInTheRunsNameSoAnAllAndANoneRunNeverShareAFile() throws Exception {
        String none = BenchRunScriptTest.last(only(Map.of("BENCH_RECORDING_LEVEL", "none")), "--name");
        String all = BenchRunScriptTest.last(only(Map.of("BENCH_RECORDING_LEVEL", "all")), "--name");
        String unset = BenchRunScriptTest.last(only(Map.of()), "--name");

        assertTrue(none.endsWith("-none"), none);
        assertTrue(all.endsWith("-all"), all);
        assertNotEquals(none, all);
        assertEquals(unset + "-none", none, "the level is appended to the name an unset level keeps");
    }

    @Test
    void theSummaryPrintsTheRecordingLevelAndWhetherTheRunSettled() throws Exception {
        JsonObject record = BenchRunScriptOpenTest.CANNED.copy().put("recordingLevel", "none");
        record.getJsonObject("open").put("settled", true);
        String settled = summary(record);
        assertTrue(settled.contains("recording level none, settled true"), settled);

        String echo = summary(BenchRunScriptOpenTest.CANNED);   // an echo record: no level, no settle
        assertTrue(echo.contains("recording level n/a, settled n/a"), echo);
    }

    /** The summary line of the one open run whose java stub writes {@code record}. */
    String summary(JsonObject record) throws Exception {
        Result r = run(Map.of(), record);
        assertEquals(0, r.exit(), r.output());
        String name = BenchRunScriptTest.last(r.runs().getFirst(), "--name");
        return r.output().lines().filter(l -> l.startsWith(name + ":")).findFirst().orElse("no summary line: " + r.output());
    }

    /** The one NodeBenchMain run of BENCH_SCENARIOS=open with {@code env}; asserts exit 0. */
    List<String> only(Map<String, String> env) throws Exception {
        Result r = run(env, BenchRunScriptOpenTest.CANNED);
        assertEquals(0, r.exit(), r.output());
        assertEquals(1, r.runs().size(), r.runs().toString());
        return r.runs().getFirst();
    }

    /** Runs a fresh copy of run.sh with BENCH_SCENARIOS=open and {@code extra}; the java stub writes {@code record}. */
    Result run(Map<String, String> extra, JsonObject record) throws Exception {
        Path dir = root.resolve("run-" + runs.incrementAndGet());
        Path script = dir.resolve("bench/run.sh");
        Files.createDirectories(script.getParent());
        Files.copy(BenchRunScriptTest.SCRIPT, script);
        Files.createDirectories(dir.resolve("flottila-bench/target"));
        Files.createFile(dir.resolve("flottila-bench/target/flottila-bench-0.1.0.jar"));
        Path canned = dir.resolve("canned.json");
        Files.writeString(canned, record.encodePrettily());
        Path bin = Files.createDirectories(dir.resolve("stubs"));
        Path log = dir.resolve("java.log");
        BenchRunScriptTest.stub(bin, "java", "printf '%s\\n' \"$*\" >> '" + log + "'\n"
                + "out=''; prev=''; for a in \"$@\"; do if [ \"$prev\" = --out ]; then out=\"$a\"; fi; prev=\"$a\"; done\n"
                + "if [ -n \"$out\" ]; then mkdir -p \"$(dirname \"$out\")\"; cp '" + canned + "' \"$out\"; fi");
        BenchRunScriptTest.stub(bin, "psql", "echo 1");
        BenchRunScriptTest.stub(bin, "mvn", "exit 0");
        BenchRunScriptTest.stub(bin, "git", "echo abc1234");
        BenchRunScriptTest.stub(bin, "python3", "if [ \"$1\" = -c ]; then exec '" + BenchRunScriptOpenTest.python3() + "' \"$@\"; fi\nexit 0");

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("HOME", dir.toString());
        env.put("BENCH_SCENARIOS", "open");
        env.putAll(extra);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "run.sh ended: " + output);
        List<String> lines = Files.exists(log) ? Files.readAllLines(log) : new ArrayList<>();
        return new Result(p.exitValue(), output, BenchRunScriptTest.nodeBenchRuns(lines));
    }
}
