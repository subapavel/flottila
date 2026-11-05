package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b: Postgres' CPU is the whole cluster's — a pid source ({@code --pg-cpu-source <postmaster pid>}) sums utime + stime of
 * the postmaster and of every process whose parent it is (its backends, checkpointer, walwriter, ...), read from
 * {@code <proc>/<pid>/stat}; {@link CpuSource#of(String, Path)} takes the proc root, here a fake one in a temp dir. Its
 * per-sample series ({@link CpuSource#sampler}) is a delta in ms per backlog sample, null with a note when unreadable.
 */
class CpuSourceClusterTest {

    @TempDir Path proc;

    /** {@code <proc>/<pid>/stat} of a process named {@code comm}, child of {@code ppid}, with {@code utime} / {@code stime} ticks. */
    void process(long pid, String comm, long ppid, long utime, long stime) throws IOException {
        Path d = Files.createDirectories(proc.resolve(String.valueOf(pid)));
        // fields: pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime ...
        Files.writeString(d.resolve("stat"), pid + " (" + comm + ") S " + ppid + " 4100 4100 0 -1 4194560 900 0 0 0 " + utime + " " + stime
                + " 0 0 20 0 1 0 12345 200000000 3000 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 1 0 0 0 0 0\n");
    }

    @Test
    void aPostmastersCpuIsItsOwnPlusEveryChildsAndNoOtherProcesss() throws IOException {
        process(4100, "postgres", 1, 100, 50);
        process(4101, "postgres: checkpointer ", 4100, 10, 5);
        process(4102, "postgres: bench openbench_1 172.17.0.1(51234) idle", 4100, 200, 100);   // a ')' inside the command
        process(4103, "postgres: walwriter ", 4100, 1, 2);
        process(4200, "java", 1, 7777, 7777);                                                 // not the cluster's
        process(4141, "postgres", 4200, 3333, 3333);                                         // another postgres, another parent
        Files.writeString(proc.resolve("meminfo"), "MemTotal: 1 kB\n");                       // not a process
        Files.createDirectories(proc.resolve("self"));

        CpuSource.Reading r = CpuSource.of("4100", proc).read();

        assertNull(r.failure(), "the cluster is readable: " + r);
        // (100 + 50) + (10 + 5) + (200 + 100) + (1 + 2) = 468 ticks of 10 ms
        assertEquals(4_680_000_000L, r.nanos(), "the postmaster and its three children, in ns: " + r);
    }

    @Test
    void aChildThatExitedWhileTheTreeWasReadIsLeftOutNotAFailure() throws IOException {
        process(4100, "postgres", 1, 100, 50);
        process(4101, "postgres: checkpointer ", 4100, 10, 5);
        Files.createDirectories(proc.resolve("4104"));   // its directory listed, its stat gone

        CpuSource.Reading r = CpuSource.of("4100", proc).read();

        assertNull(r.failure(), "a backend exiting mid-read is not an unreadable cluster: " + r);
        assertEquals(1_650_000_000L, r.nanos(), "165 ticks: " + r);
    }

    @Test
    void aPostmasterThatIsNotThereIsUnreadableNamingItsPid() throws IOException {
        process(4200, "java", 1, 7777, 7777);

        CpuSource.Reading r = CpuSource.of("4100", proc).read();

        assertNotNull(r.failure(), "no such postmaster");
        assertTrue(r.failure().contains("4100"), "the reason names the pid: " + r.failure());
    }

    @Test
    void thePostgresCpuSeriesIsTheMillisecondsSpentPerBacklogSample() {
        Iterator<CpuSource.Reading> readings = List.of(new CpuSource.Reading(1_000_000_000L, null), new CpuSource.Reading(1_250_000_000L, null),
                new CpuSource.Reading(2_250_000_000L, null)).iterator();
        OpenLoop.Sampler sampler = CpuSource.sampler(readings::next);
        for (int k = 0; k < 3; k++) sampler.sample();

        JsonObject series = sampler.series();
        assertNotNull(series.getJsonArray("cpuMs"), series.encode());
        assertEquals(3, series.getJsonArray("cpuMs").size(), "one entry per sample: " + series.encode());
        assertEquals(List.of(250.0, 1000.0), DbSamplerTest.doubles(series.getJsonArray("cpuMs")).subList(1, 3), series.encode());
    }

    @Test
    void anUnreadablePostgresCpuSeriesIsNullWithTheSourcesReasonNeverZero() {
        OpenLoop.Sampler sampler = CpuSource.sampler(() -> new CpuSource.Reading(0, "cannot read the CPU of /proc/4100/stat"));
        for (int k = 0; k < 3; k++) sampler.sample();

        DbSamplerTest.assertUnknown(sampler.series(), "cpuMs", "/proc/4100/stat");
    }
}
