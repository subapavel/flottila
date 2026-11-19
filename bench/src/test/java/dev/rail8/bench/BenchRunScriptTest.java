package dev.rail8.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bench/run.sh} run for real on a copy of the script, with {@code psql}, {@code git}, {@code java} and {@code python3}
 * stubbed first on the PATH and the repository's {@code mvnw} stubbed in the copy's root: the java stub logs each command line,
 * so the test sees which node bench runs the script starts, in order, without a database.
 */
class BenchRunScriptTest {

    static final Path SCRIPT = Path.of("../bench/run.sh");

    @TempDir Path root;

    @Test
    void theAckScenarioRunsTheNodeBenchAtConcurrencyOneEightAndSixtyFour() throws Exception {
        List<List<String>> runs = nodeBenchRuns(run("ack"));
        List<Integer> concurrency = runs.stream().map(a -> Integer.parseInt(last(a, "--concurrency"))).toList();
        assertEquals(List.of(1, 8, 64), concurrency, "the ack loop's runs, in order: " + runs);
        for (List<String> a : runs) assertEquals("ack", last(a, "--scenario"), "every run of the ack loop is the ack scenario: " + a);
        List<String> outs = runs.stream().map(a -> last(a, "--out")).toList();
        assertEquals(outs.size(), new HashSet<>(outs).size(), "each run writes its own JSON, none overwrites another: " + outs);
    }

    @Test
    void theAtMostOnceScenarioRunsTheNodeBenchWithEachRecordingPayload() throws Exception {
        List<List<String>> runs = nodeBenchRuns(run("at-most-once"));
        assertEquals(List.of("keep", "metadata-only"), runs.stream().map(a -> last(a, "--recording")).toList(), "in order: " + runs);
        for (List<String> a : runs) assertEquals("at-most-once", last(a, "--scenario"), a.toString());
        assertEquals(List.of("node-at-most-once-keep", "node-at-most-once-metadata-only"), runs.stream().map(a -> last(a, "--name")).toList());
    }

    @Test
    void anAtMostOnceRunThatExitsThreeWithRefusalsAloneStands() throws Exception {
        // NodeBenchMain exits 3 on any failed request; a record whose errors are 0 failed by 503s only, the mode's backpressure
        List<List<String>> runs = nodeBenchRuns(run("at-most-once", "exit 3", "echo 0"));
        assertEquals(2, runs.size(), "both payloads ran, the first run's exit 3 did not stop the script: " + runs);
    }

    // --- fixtures

    /** The script's java command lines that start NodeBenchMain, each as its arguments. */
    static List<List<String>> nodeBenchRuns(List<String> javaLines) {
        return javaLines.stream().map(l -> Arrays.asList(l.split(" "))).filter(a -> a.contains("dev.rail8.bench.NodeBenchMain")).toList();
    }

    /** The value after the last {@code key} in {@code args} (a later option overrides an earlier one). */
    static String last(List<String> args, String key) {
        int at = args.lastIndexOf(key);
        assertTrue(at >= 0 && at + 1 < args.size(), key + " in " + args);
        return args.get(at + 1);
    }

    /** Runs a copy of run.sh with {@code BENCH_SCENARIOS=scenarios}; asserts exit 0; the java stub's log, one line per call. */
    List<String> run(String scenarios) throws IOException, InterruptedException {
        return run(scenarios, "exit 0", "exit 0");
    }

    /**
     * Runs a copy of run.sh as {@link #run(String)} does, the java stub ending with {@code javaExit} after it logged its command
     * line and the python3 stub's body {@code python3}.
     */
    List<String> run(String scenarios, String javaExit, String python3) throws IOException, InterruptedException {
        Path script = root.resolve("bench/run.sh");
        Files.createDirectories(script.getParent());
        Files.copy(SCRIPT, script);
        Files.createDirectories(root.resolve("bench/target"));
        Files.createFile(root.resolve("bench/target/rail8-bench-0.1.0.jar"));
        Path bin = Files.createDirectories(root.resolve("stubs"));
        Path log = root.resolve("java.log");
        stub(bin, "java", "printf '%s\\n' \"$*\" >> '" + log + "'\n" + javaExit);
        stub(bin, "psql", "echo 1");
        stub(root, "mvnw", "exit 0");
        stub(bin, "git", "echo abc1234");
        stub(bin, "python3", python3);

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("HOME", root.toString());
        env.put("BENCH_SCENARIOS", scenarios);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "run.sh ended: " + output);
        assertEquals(0, p.exitValue(), "run.sh exits 0: " + output);
        return Files.exists(log) ? Files.readAllLines(log) : new ArrayList<>();
    }

    static void stub(Path bin, String name, String body) throws IOException {
        Path file = bin.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
