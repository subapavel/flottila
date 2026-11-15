package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b, the implementer's cases on the cluster's CPU (decision 3): the postmaster's cutime + cstime — its exited backends —
 * count, and so do a child's (what it ran and reaped, an archive_command), each process' four fields; a postmaster whose stat
 * cannot be parsed is unreadable naming it; the per-sample series starts at 0 and leaves an unread sample's entries null.
 */
class CpuSourceClusterCasesTest {

    @TempDir Path proc;

    /** {@code <proc>/<pid>/stat} with utime, stime, cutime and cstime in ticks. */
    void process(long pid, long ppid, long utime, long stime, long cutime, long cstime) throws IOException {
        Path d = Files.createDirectories(proc.resolve(String.valueOf(pid)));
        Files.writeString(d.resolve("stat"), pid + " (postgres) S " + ppid + " 4100 4100 0 -1 4194560 900 0 0 0 " + utime + " " + stime + " "
                + cutime + " " + cstime + " 20 0 1 0 12345 200000000 3000 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 1 0 0 0 0 0\n");
    }

    @Test
    void theExitedBackendsAPostmasterWaitedForAndWhatAChildReapedCountWithTheirOwnTime() throws IOException {
        process(4100, 1, 100, 50, 3000, 2000);   // 5000 ticks of backends that exited
        process(4101, 4100, 10, 5, 7, 3);         // the archiver: 10 ticks of archive_command shells it reaped
        process(4200, 1, 1, 1, 900, 900);         // not the cluster's, nor its c-times

        CpuSource.Reading r = CpuSource.of("4100", proc).read();

        assertNull(r.failure(), String.valueOf(r));
        // (100 + 50 + 3000 + 2000) + (10 + 5 + 7 + 3) = 5175 ticks of 10 ms
        assertEquals(51_750_000_000L, r.nanos(), String.valueOf(r));
    }

    @Test
    void aPostmasterStatThatCannotBeParsedIsUnreadableNamingIt() throws IOException {
        Path d = Files.createDirectories(proc.resolve("4100"));
        Files.writeString(d.resolve("stat"), "4100 (postgres) S\n");

        CpuSource.Reading r = CpuSource.of("4100", proc).read();

        assertNotNull(r.failure(), String.valueOf(r));
        assertTrue(r.failure().contains(d.resolve("stat").toString()), r.failure());
    }

    @Test
    void aCgroupUnderAProcRootIsStillACgroup() throws IOException {
        Path cgroup = Files.createDirectories(proc.resolve("postgresql.service"));
        CpuSourceTest.cpuStat(cgroup, 2_000_000);

        assertEquals(new CpuSource.Reading(2_000_000_000L, null), CpuSource.of(cgroup.toString(), proc).read());
    }

    @Test
    void theSeriesStartsAtZeroAndAnUnreadSampleLeavesItsEntriesNullWithItsReason() {
        Iterator<CpuSource.Reading> readings = List.of(new CpuSource.Reading(5_000_000_000L, null), new CpuSource.Reading(0, "cannot read the CPU of x"),
                new CpuSource.Reading(5_500_000_000L, null), new CpuSource.Reading(5_750_000_000L, null)).iterator();
        OpenLoop.Sampler sampler = CpuSource.sampler(readings::next);
        for (int k = 0; k < 4; k++) sampler.sample();

        JsonObject series = sampler.series();
        assertEquals(Arrays.asList(0.0, null, null, 250.0), DbSamplerTest.doubles(series.getJsonArray("cpuMs")), series.encode());
        assertEquals("cannot read the CPU of x", series.getJsonObject("notes").getString("cpuMs"), series.encode());
    }
}
