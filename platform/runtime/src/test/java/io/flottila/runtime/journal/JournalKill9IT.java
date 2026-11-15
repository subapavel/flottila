package io.flottila.runtime.journal;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every acknowledged record survives a kill -9 (CORE-3 items 3 and 9): {@link JournalKill9Child} appends from eight
 * virtual threads and prints each record only after its {@code forced} future succeeded; the child is killed with -9
 * (SIGKILL, {@link Process#destroyForcibly()}) once {@value #KILL_AFTER} records were acknowledged, while it still appends;
 * a journal opened on the same directory then reads every printed ref back byte-equal. The kill is triggered by a count
 * of acknowledgements, never by a timer. Pattern of MainProcessIT / CpuSamplerIT: the child runs on this test's class
 * path with an empty environment and, when this JVM runs under JaCoCo, the same agent appending to
 * {@code jacoco-main-process.exec}.
 */
class JournalKill9IT {

    static final int RECORD_MAX = 16 * 1024;
    static final int KILL_AFTER = 400;
    static final long BOUND_SECONDS = 60;

    @TempDir Path tmp;

    /** Small segments: the child rolls and seals while it runs. */
    static JournalConfig config(Path dir) {
        return JournalFixture.settings(dir).recordMax(RECORD_MAX).segmentBytes(256 * 1024).segmentRecords(64).config();
    }

    record Ack(String id, JournalRef ref) {}

    @Test
    void everyAcknowledgedRecordSurvives() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("journal"));
        Path stderr = tmp.resolve("child-stderr.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        jacocoAgent().ifPresent(command::add);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                JournalKill9Child.class.getName(), dir.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(stderr.toFile());
        builder.environment().clear();
        Process child = builder.start();

        List<String> lines = new CopyOnWriteArrayList<>();
        AtomicInteger acks = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(1);
        Thread reader = Thread.ofVirtual().name("kill9-reader").start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    lines.add(line);
                    if (line.startsWith("ack ") && acks.incrementAndGet() >= KILL_AFTER) enough.countDown();
                }
            } catch (IOException e) {
                lines.add("reader failed: " + e);
            }
        });
        try {
            if (!enough.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
                fail("the child acknowledged " + acks.get() + " of " + KILL_AFTER + " records within " + BOUND_SECONDS + " s; out: "
                        + tail(lines, 5) + "; err: " + Files.readString(stderr));
            }
            assertTrue(child.isAlive(), () -> "killed mid-run, while it still appends: " + tail(lines, 3));
        } finally {
            child.destroyForcibly();
        }
        assertTrue(child.waitFor(BOUND_SECONDS, TimeUnit.SECONDS), "the killed child is gone");
        assertEquals(137, child.exitValue(), "killed by SIGKILL (128 + 9), not exited: " + Files.readString(stderr));
        reader.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));

        List<String> errors = lines.stream().filter(l -> l.startsWith("error ")).toList();
        assertEquals(List.of(), errors, "no append failed in the child before the kill");
        List<Ack> acknowledged = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split(" ");
            if (f.length == 6 && f[0].equals("ack") && f[5].equals("end")) {   // a line cut by the kill has no "end"
                acknowledged.add(new Ack(f[1], new JournalRef(Long.parseLong(f[2]), Long.parseLong(f[3]), Integer.parseInt(f[4]))));
            }
        }
        assertTrue(acknowledged.size() >= KILL_AFTER, acknowledged.size() + " acknowledged lines read back");
        Set<Long> segments = new HashSet<>();
        acknowledged.forEach(a -> segments.add(a.ref().segment()));
        assertTrue(segments.size() > 1, "the run rolled at least once before the kill: " + segments);

        FakeClock clock = FakeClock.at(JournalFixture.START);
        Journal reopened = Journal.open(config(dir), clock, new FakeTimer(clock), JournalFixture.keys(JournalFixture.KEY), JournalFiles.real());
        try {
            for (Ack a : acknowledged) {
                assertArrayEquals(JournalFixture.body(a.id(), JournalKill9Child.size(a.id())), reopened.read(a.ref()),
                        "acknowledged " + a.id() + " at " + a.ref() + " survives the kill byte-equal");
            }
        } finally {
            reopened.close();
        }
    }

    /**
     * The test JVM's JaCoCo agent for the child (as MainProcessIT): the same jar and options, the destfile
     * {@code jacoco-main-process.exec} beside the test JVM's, appended to; empty without the agent (an IDE run).
     */
    static Optional<String> jacocoAgent() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("org.jacoco.agent"))
                .map(arg -> arg.substring("-javaagent:".length())).findFirst()
                .map(spec -> {
                    int eq = spec.indexOf('=');
                    String jar = eq < 0 ? spec : spec.substring(0, eq);
                    Path destfile = Path.of("target", "jacoco.exec");
                    List<String> options = new ArrayList<>();
                    for (String option : eq < 0 ? new String[0] : spec.substring(eq + 1).split(",")) {
                        if (option.startsWith("destfile=")) destfile = Path.of(option.substring("destfile=".length()));
                        else if (!option.startsWith("append=") && !option.isEmpty()) options.add(option);
                    }
                    options.add("destfile=" + destfile.resolveSibling("jacoco-main-process.exec").toAbsolutePath());
                    options.add("append=true");
                    return "-javaagent:" + jar + "=" + String.join(",", options);
                });
    }

    /** The last {@code n} lines, from a snapshot: the reader thread appends while the message is built (FIX-160). */
    static List<String> tail(List<String> lines, int n) {
        List<String> snapshot = List.copyOf(lines);
        return snapshot.subList(Math.max(0, snapshot.size() - n), snapshot.size());
    }
}
