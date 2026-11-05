package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE-15b, decision 6: the open record's series keys — {@code db} (from the run's database source; the echo self-test has
 * none), {@code pgCpu} (the Postgres cluster's CPU per sample, from {@code --pg-cpu-source}) and {@code io} (the
 * {@code --io-device} counters) — each read from its own source ({@link NodeBenchMain.Harness#samplers}), over a fake proc root
 * and diskstats; and the window's {@code cpuMs.postgres} reads the same cluster.
 */
class NodeBenchMainSamplersTest {

    @TempDir Path proc;

    static NodeBenchMain.Open open(String... extra) {
        String[] args = new String[4 + extra.length];
        System.arraycopy(new String[] {"--scenario", "ack", "--rate", "100"}, 0, args, 0, 4);
        System.arraycopy(extra, 0, args, 4, extra.length);
        return NodeBenchMain.options(args).open();
    }

    void stat(long pid, long ppid, long utime) throws IOException {
        Path d = Files.createDirectories(proc.resolve(String.valueOf(pid)));
        Files.writeString(d.resolve("stat"), pid + " (postgres) S " + ppid + " 1 1 0 -1 0 0 0 0 0 " + utime + " 0 0 0 20 0 1 0 1 1 1\n");
    }

    @Test
    void theRecordKeysAreDbPgCpuAndIoEachReadFromItsOwnSource() throws IOException {
        stat(4100, 1, 100);
        stat(4101, 4100, 20);
        Path diskstats = proc.resolve("diskstats");
        Files.writeString(diskstats, DiskSamplerTest.diskstats(1000, 2000, 300, 400));
        AtomicLong now = new AtomicLong(DiskSamplerTest.T0);
        Iterator<JsonObject> db = List.of(DbSamplerTest.reading(10, 1, 1.0, 1, 1, 1, 1, 1, 1), DbSamplerTest.reading(17, 1, 1.0, 1, 1, 1, 1, 1, 1))
                .iterator();

        Map<String, OpenLoop.Sampler> samplers = NodeBenchMain.Harness.samplers(open("--pg-cpu-source", "4100", "--io-device", "nvme0n1"),
                db::next, proc, diskstats, now::get);
        assertEquals(List.of("db", "pgCpu", "io"), List.copyOf(samplers.keySet()), "the record's keys");
        samplers.values().forEach(OpenLoop.Sampler::sample);
        stat(4101, 4100, 45);                                              // the backend spent 25 ticks
        Files.writeString(diskstats, DiskSamplerTest.diskstats(1003, 2000, 300, 650));
        now.addAndGet(DiskSamplerTest.SECOND);
        samplers.values().forEach(OpenLoop.Sampler::sample);

        assertEquals(List.of(0L, 7L), DbSamplerTest.longs(samplers.get("db").series().getJsonArray("xactCommit")));
        assertEquals(List.of(0.0, 250.0), DbSamplerTest.doubles(samplers.get("pgCpu").series().getJsonArray("cpuMs")), "the cluster's 25 ticks");
        JsonObject io = samplers.get("io").series();
        assertEquals(List.of(0L, 3L), DbSamplerTest.longs(io.getJsonArray("reads")), io.encode());
        assertEquals(List.of(0.0, 25.0), DbSamplerTest.doubles(io.getJsonArray("utilPercent")), io.encode());
        assertEquals(new CpuSource.Reading(1_450_000_000L, null), NodeBenchMain.Harness.postgresCpu(open("--pg-cpu-source", "4100"), proc).get(),
                "the window's postgres figure is the cluster's too: (100 + 45) ticks");
    }

    @Test
    void withoutADatabaseNoDbAndWithoutTheOptionsPgCpuAndIoAreUnknownSayingWhy() {
        Map<String, OpenLoop.Sampler> samplers = NodeBenchMain.Harness.samplers(open(), null, proc, proc.resolve("diskstats"), () -> 0);
        assertEquals(List.of("pgCpu", "io"), List.copyOf(samplers.keySet()), "the echo self-test has no database");
        samplers.values().forEach(OpenLoop.Sampler::sample);

        DbSamplerTest.assertUnknown(samplers.get("pgCpu").series(), "cpuMs", "no --pg-cpu-source");
        DbSamplerTest.assertUnknown(samplers.get("io").series(), "reads", "no --io-device");
    }
}
