package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b: {@code bench/run.sh}'s open scenario and the database series. {@code BENCH_IO_DEVICE} is passed as
 * {@code --io-device}; {@code BENCH_PG_STATEMENTS} creates the pg_stat_statements extension in {@code BENCH_DB} before the run
 * (a refusal leaves the run going: its record then says the statements are unknown); the summary prints the window's totals —
 * commits/s and WAL syncs/s (the {@code db} series summed over the window) and the top statement by time — and "n/a" for an
 * unknown one. Runs a copy of the script with {@link BenchRunScriptOpenTest}'s stubs; java and psql log their command lines to
 * one file, in call order.
 */
class BenchRunScriptOpenDbTest {

    @TempDir Path root;

    record Result(int exit, String output, List<String> calls) {
        /** The java command lines that start NodeBenchMain, each as its arguments. */
        List<List<String>> runs() {
            return calls.stream().filter(l -> l.startsWith("java ")).map(l -> Arrays.asList(l.substring(5).split(" ")))
                    .filter(a -> a.contains("io.flottila.bench.NodeBenchMain")).toList();
        }

        List<String> psql() {
            return calls.stream().filter(l -> l.startsWith("psql ")).toList();
        }
    }

    /** A 2 s window at 5000/s (10 000 scheduled, backlog sampled at 0, 1 and 2 s) with every field known but those in db. */
    static JsonObject record(JsonObject db) {
        return new JsonObject().put("name", "canned").put("scenario", "ack").put("recordingLevel", "none").put("open", new JsonObject()
                .put("offeredRate", 5000).put("scheduled", 10000).put("sent", 9990).put("achievedRate", 4995.0).put("accepted", 9980)
                .put("acceptedTotal", 9980).put("refused", new JsonObject()).put("refused503", 0).put("timeouts", 0).put("errors", 0)
                .put("clientSaturated", 0).put("p50Ms", 3.25).put("p99Ms", 12.5).put("p999Ms", 48.25).put("maxMs", 301.5).put("receipts", 9970)
                .put("backlog", new JsonArray().add(0).add(10).add(20)).put("backlogGrowth", 20).put("backlogGrowthPercent", 0.2)
                .put("cpuMs", new JsonObject().put("bench", 91000.0).put("node", 4321.0).put("postgres", 6543.0)).put("cpuNotes", new JsonObject())
                .put("valid", true).put("invalidReasons", new JsonArray()).put("settled", true).put("db", db));
    }

    /** 14 000 commits and 6 200 WAL syncs over the 2 s window; the claim the statement with the most time (8125 ms). */
    static JsonObject db() {
        return new JsonObject().put("xactCommit", new JsonArray().add(0).add(7001).add(6999)).put("walSync", new JsonArray().add(0).add(3100).add(3100))
                .put("statements", new JsonArray()
                        .add(new JsonObject().put("queryid", 42).put("query", "with taken as (update work w set locked_by = $1, locked_until = now()")
                                .put("calls", 9000).put("totalExecTimeMs", 8125.0).put("sharedBlks", 77777))
                        .add(new JsonObject().put("queryid", 43).put("query", "delete from work where seq = $1 and attempts = $2")
                                .put("calls", 8999).put("totalExecTimeMs", 2222.0).put("sharedBlks", 55555)))
                .put("notes", new JsonObject());
    }

    @Test
    void benchIoDeviceIsPassedAsIoDeviceAndUnsetPassesNone() throws Exception {
        Result set = run(record(db()), Map.of("BENCH_IO_DEVICE", "nvme0n1"));
        assertEquals(0, set.exit(), set.output());
        assertEquals(1, set.runs().size(), set.calls().toString());
        assertEquals("nvme0n1", BenchRunScriptTest.last(set.runs().getFirst(), "--io-device"), set.runs().toString());

        Result unset = run(record(db()), Map.of());
        assertEquals(0, unset.exit(), unset.output());
        assertFalse(unset.runs().getFirst().contains("--io-device"), "no device unless BENCH_IO_DEVICE names one: " + unset.runs());
    }

    @Test
    void benchPgStatementsCreatesTheExtensionInTheBenchDatabaseBeforeTheRun() throws Exception {
        Result r = run(record(db()), Map.of("BENCH_PG_STATEMENTS", "1", "BENCH_DB", "bench_db_x"));
        assertEquals(0, r.exit(), r.output());
        int create = indexOf(r.calls(), l -> l.startsWith("psql ") && l.toLowerCase(Locale.ROOT).contains("create extension")
                && l.contains("pg_stat_statements"));
        assertTrue(create >= 0, "psql created the extension: " + r.calls());
        assertTrue(r.calls().get(create).contains("bench_db_x"), "in the bench database, where the node's statements run: " + r.calls().get(create));
        int bench = indexOf(r.calls(), l -> l.startsWith("java ") && l.contains("io.flottila.bench.NodeBenchMain"));
        assertTrue(bench > create, "before the run: " + r.calls());
    }

    @Test
    void withoutBenchPgStatementsNoExtensionIsCreated() throws Exception {
        Result r = run(record(db()), Map.of());
        assertEquals(0, r.exit(), r.output());
        assertEquals(1, r.runs().size(), r.calls().toString());
        assertTrue(r.psql().stream().noneMatch(l -> l.contains("pg_stat_statements")), "no extension unless asked: " + r.psql());
    }

    @Test
    void aRefusedExtensionLeavesTheRunGoingAndSaysSo() throws Exception {
        Result r = run(record(db()), Map.of("BENCH_PG_STATEMENTS", "1", "REFUSE_EXTENSION", "1"));
        assertEquals(0, r.exit(), "the run is not lost to a missing extension: " + r.output());
        assertEquals(1, r.runs().size(), "the node bench still ran: " + r.calls());
        assertTrue(r.output().contains("pg_stat_statements"), "the output says the extension was not created: " + r.output());
    }

    @Test
    void theSummaryPrintsTheWindowsCommitsAndWalSyncsPerSecondAndTheTopStatementByTime() throws Exception {
        Result r = run(record(db()), Map.of("BENCH_RATE", "5000", "BENCH_RAMP", "PT0S", "BENCH_WINDOW", "PT2S"));
        assertEquals(0, r.exit(), r.output());
        String line = summary(r);
        assertTrue(line.contains("7000"), "14 000 commits over 2 s = 7000/s: " + line);
        assertTrue(line.contains("3100"), "6 200 WAL syncs over 2 s = 3100/s: " + line);
        assertTrue(line.contains("with taken as"), "the top statement by time is named: " + line);
        assertTrue(line.contains("8125"), "with its time in the window: " + line);
        assertFalse(line.contains("delete from work"), "only the top one: " + line);
    }

    @Test
    void theSummarySaysNaForUnknownWalSyncsAndStatementsNeverZero() throws Exception {
        JsonObject db = db().putNull("walSync").putNull("statements").put("notes", new JsonObject().put("walSync", "fsync is off")
                .put("statements", "pg_stat_statements is not installed in flottila_bench"));
        Result r = run(record(db), Map.of("BENCH_RATE", "5000", "BENCH_RAMP", "PT0S", "BENCH_WINDOW", "PT2S"));
        assertEquals(0, r.exit(), r.output());
        String line = summary(r);
        assertTrue(line.contains("7000"), "the commits are still known: " + line);
        assertTrue(line.contains("n/a"), "an unknown total is n/a (every other field of the record is known): " + line);
        assertFalse(line.contains("3100"), line);
    }

    // --- fixtures

    static int indexOf(List<String> calls, java.util.function.Predicate<String> p) {
        for (int i = 0; i < calls.size(); i++) if (p.test(calls.get(i))) return i;
        return -1;
    }

    /** The values of the open run's summary line (after "<name>:"). */
    static String summary(Result r) {
        String name = BenchRunScriptTest.last(r.runs().getFirst(), "--name");
        String full = r.output().lines().filter(l -> l.startsWith(name + ":")).findFirst().orElse("");
        assertFalse(full.isEmpty(), "a summary line for " + name + ": " + r.output());
        return full.substring(name.length() + 1);
    }

    /**
     * Runs a fresh copy of run.sh with BENCH_SCENARIOS=open and {@code extra}; the java stub writes {@code record} to the run's
     * {@code --out}; psql answers 1, or fails a pg_stat_statements call when REFUSE_EXTENSION is set.
     */
    Result run(JsonObject record, Map<String, String> extra) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory(root, "run");
        Path script = dir.resolve("bench/run.sh");
        Files.createDirectories(script.getParent());
        Files.copy(BenchRunScriptTest.SCRIPT, script);
        Files.createDirectories(dir.resolve("flottila-bench/target"));
        Files.createFile(dir.resolve("flottila-bench/target/flottila-bench-0.1.0.jar"));
        Path canned = dir.resolve("canned.json");
        Files.writeString(canned, record.encodePrettily());
        Path bin = Files.createDirectories(dir.resolve("stubs"));
        Path log = dir.resolve("calls.log");
        BenchRunScriptTest.stub(bin, "java", "printf 'java %s\\n' \"$*\" >> '" + log + "'\n"
                + "out=''; prev=''; for a in \"$@\"; do if [ \"$prev\" = --out ]; then out=\"$a\"; fi; prev=\"$a\"; done\n"
                + "if [ -n \"$out\" ]; then mkdir -p \"$(dirname \"$out\")\"; cp '" + canned + "' \"$out\"; fi");
        BenchRunScriptTest.stub(bin, "psql", "printf 'psql %s\\n' \"$*\" >> '" + log + "'\n"
                + "case \"$*\" in *pg_stat_statements*) if [ -n \"$REFUSE_EXTENSION\" ]; then"
                + " echo 'ERROR:  could not open extension control file pg_stat_statements' >&2; exit 1; fi ;; esac\necho 1");
        BenchRunScriptTest.stub(bin, "mvn", "exit 0");
        BenchRunScriptTest.stub(bin, "git", "echo abc1234");
        BenchRunScriptTest.stub(bin, "python3", "if [ \"$1\" = -c ]; then exec '" + BenchRunScriptOpenTest.python3() + "' \"$@\"; fi\nexit 0");

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString()).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("HOME", dir.toString());
        env.put("BENCH_SCENARIOS", "open");
        Map<String, String> all = new HashMap<>(Map.of("BENCH_RATE", "5000", "BENCH_RAMP", "PT0S", "BENCH_WINDOW", "PT2S"));
        all.putAll(extra);
        env.putAll(all);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "run.sh ended: " + output);
        List<String> calls = Files.exists(log) ? Files.readAllLines(log) : new ArrayList<>();
        return new Result(p.exitValue(), output, calls);
    }
}
