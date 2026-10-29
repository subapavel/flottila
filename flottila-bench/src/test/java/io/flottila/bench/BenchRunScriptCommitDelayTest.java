package io.flottila.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-08b: {@code bench/run.sh}'s {@code BENCH_COMMIT_DELAY}, run for real on a copy of the script with {@code psql}, {@code java}
 * and the rest stubbed first on the PATH; both stubs log their command lines to one file, so the test sees the order of the
 * server-wide setting, the runs and the reset.
 */
class BenchRunScriptCommitDelayTest {

    static final String SET = "alter system set commit_delay = 250";
    static final String RESET = "alter system reset commit_delay";
    static final String RELOAD = "select pg_reload_conf()";

    @TempDir Path root;

    @Test
    void theAckRunsRunUnderTheDelayItsRunsCarryItInTheirNamesAndTheServerIsResetAfter() throws Exception {
        Result r = run("250", "250", 0);
        assertEquals(0, r.exit(), r.output());
        List<String> log = r.log();
        int set = index(log, "psql", SET);
        assertEquals(set + 1, index(log, "psql", RELOAD), "a reload follows the ALTER SYSTEM: " + log);
        int checked = index(log, "psql", "where name = 'commit_delay'");
        List<Integer> runs = IntStream.range(0, log.size()).filter(i -> log.get(i).startsWith("java ")).boxed().toList();
        assertEquals(3, runs.size(), "the ack loop's three runs: " + log);
        assertTrue(set < checked && checked < runs.getFirst(), "the runs start once a new session shows the value: " + log);
        List<String> outs = runs.stream().map(i -> BenchRunScriptTest.last(List.of(log.get(i).split(" ")), "--out")).toList();
        assertEquals(List.of("bench/out/node-ack-c1-cd250.json", "bench/out/node-ack-c8-cd250.json", "bench/out/node-ack-c64-cd250.json"),
                outs, "each run is named after its delay: " + log);
        int reset = index(log, "psql", RESET);
        assertTrue(reset > runs.getLast(), "the reset comes after the last run: " + log);
        assertEquals(RELOAD, tail(log.get(reset + 1)), "a reload follows the reset: " + log);
    }

    @Test
    void aFailedRunStillResetsTheServer() throws Exception {
        Result r = run("250", "250", 1);
        assertTrue(r.exit() != 0, "a failed java run fails the script: " + r.output());
        int reset = index(r.log(), "psql", RESET);
        assertTrue(reset > index(r.log(), "java", "NodeBenchMain"), "reset after the failed run: " + r.log());
        assertEquals(RELOAD, tail(r.log().get(reset + 1)), "and reloaded: " + r.log());
    }

    @Test
    void aValueTheServerNeverShowsStopsBeforeAnyRunAndResets() throws Exception {
        Result r = run("250", "0", 0);
        assertEquals(4, r.exit(), r.output());
        assertTrue(r.output().contains("commit_delay did not become 250"), r.output());
        assertTrue(r.log().stream().noneMatch(l -> l.startsWith("java ")), "no run under a value the server does not show: " + r.log());
        assertTrue(index(r.log(), "psql", RESET) > index(r.log(), "psql", SET), "reset after the set: " + r.log());
    }

    @Test
    void aValueOutsideZeroToOneHundredThousandIsRefusedBeforeAnythingRuns() throws Exception {
        for (String value : List.of("abc", "-1", "100001", "1; drop table x")) {
            Result r = run(value, "0", 0);
            assertEquals(2, r.exit(), value + ": " + r.output());
            assertTrue(r.output().contains("BENCH_COMMIT_DELAY"), r.output());
            assertTrue(r.log().isEmpty(), value + " ran nothing: " + r.log());
        }
    }

    @Test
    void withoutTheVariableTheServerIsNeverAlteredAndTheRunsKeepTheirNames() throws Exception {
        Result r = run(null, "0", 0);
        assertEquals(0, r.exit(), r.output());
        assertTrue(r.log().stream().noneMatch(l -> l.contains("alter system") || l.contains("pg_reload_conf")), r.log().toString());
        List<String> outs = r.log().stream().filter(l -> l.startsWith("java ")).map(l -> BenchRunScriptTest.last(List.of(l.split(" ")), "--out")).toList();
        assertEquals(List.of("bench/out/node-ack-c1.json", "bench/out/node-ack-c8.json", "bench/out/node-ack-c64.json"), outs);
    }

    // --- fixtures

    record Result(int exit, String output, List<String> log) {}

    /** The index of the first log line of {@code tool} that contains {@code text}; asserts there is one. */
    static int index(List<String> log, String tool, String text) {
        for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith(tool + " ") && log.get(i).contains(text)) return i;
        throw new AssertionError(tool + " '" + text + "' not in " + log);
    }

    /** A psql line's statement: what follows its last {@code -tAc}. */
    static String tail(String psqlLine) {
        return psqlLine.substring(psqlLine.lastIndexOf("-tAc ") + 5);
    }

    /**
     * run.sh with {@code BENCH_SCENARIOS=ack} and {@code BENCH_COMMIT_DELAY=delay} (unset when null); the psql stub answers
     * {@code shown} to the pg_settings check and 1 otherwise, the java stub exits {@code javaExit}.
     */
    Result run(String delay, String shown, int javaExit) throws IOException, InterruptedException {
        Path script = root.resolve("bench/run.sh");
        Files.createDirectories(script.getParent());
        Files.copy(BenchRunScriptTest.SCRIPT, script);
        Files.createDirectories(root.resolve("flottila-bench/target"));
        Files.createFile(root.resolve("flottila-bench/target/flottila-bench-0.1.0.jar"));
        Path bin = Files.createDirectories(root.resolve("stubs"));
        Path log = root.resolve("calls.log");
        BenchRunScriptTest.stub(bin, "java", "printf 'java %s\\n' \"$*\" >> '" + log + "'; exit " + javaExit);
        BenchRunScriptTest.stub(bin, "psql", "printf 'psql %s\\n' \"$*\" >> '" + log + "'\n"
                + "case \"$*\" in *\"where name = 'commit_delay'\"*) echo " + shown + " ;; *) echo 1 ;; esac");
        BenchRunScriptTest.stub(bin, "mvn", "exit 0");
        BenchRunScriptTest.stub(bin, "git", "echo abc1234");
        BenchRunScriptTest.stub(bin, "python3", "exit 0");
        BenchRunScriptTest.stub(bin, "sleep", "exit 0");

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("HOME", root.toString());
        env.put("BENCH_SCENARIOS", "ack");
        if (delay != null) env.put("BENCH_COMMIT_DELAY", delay);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "run.sh ended: " + output);
        List<String> calls = Files.exists(log) ? Files.readAllLines(log) : List.of();
        // the database-exists probe runs first whatever the variable; drop it so the log holds what the variable caused and the runs
        List<String> rest = calls.stream().filter(l -> !l.contains("from pg_database")).toList();
        assertFalse(rest.stream().anyMatch(l -> l.contains("create database")), "the stub answers the database exists: " + calls);
        Files.deleteIfExists(log);
        Files.deleteIfExists(root.resolve("flottila-bench/target/flottila-bench-0.1.0.jar"));
        Files.delete(script);
        return new Result(p.exitValue(), output, rest);
    }
}
