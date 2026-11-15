package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CORE-15b: {@code --io-device <name>} ({@link DiskSampler}) reads the device's line of a {@code /proc/diskstats} file at each
 * backlog sample — reads completed (field 4), writes completed (8), ms writing (11), ms doing I/O (13) — into the record's
 * {@code io} object as per-sample deltas, and {@code utilPercent}, the ms doing I/O over the sample's own interval on the
 * sampler's clock. A fake diskstats in a temp dir, rewritten between samples; a fake nanosecond clock. A device the file does not
 * list, or a file that cannot be read, makes every field null with a note naming it — never 0.
 */
class DiskSamplerTest {

    static final long T0 = 7_000_000_000_123L;
    static final long SECOND = 1_000_000_000L;

    @TempDir Path dir;

    /** A diskstats with sda, then the device's first partition (a name the device's is a prefix of, its counters × 7), then the device. */
    static String diskstats(long reads, long writes, long writeMs, long ioMs) {
        return "   8       0 sda 11 0 88 5 22 0 176 9 0 13 14 0 0 0 0 0 0\n"
                + " 259       1 nvme0n1p1 " + reads * 7 + " 3 8000 400 " + writes * 7 + " 9 64000 " + writeMs * 7 + " 0 " + ioMs * 7
                + " 99999 0 0 0 0 0 0\n"
                + " 259       0 nvme0n1 " + reads + " 3 8000 400 " + writes + " 9 64000 " + writeMs + " 0 " + ioMs + " 99999 0 0 0 0 0 0\n";
    }

    @Test
    void theDevicesCountersAreDeltasPerSampleAndItsUtilisationIsIoTimeOverEachSamplesOwnInterval() throws IOException {
        Path stats = dir.resolve("diskstats");
        AtomicLong now = new AtomicLong(T0);
        DiskSampler sampler = new DiskSampler(stats, "nvme0n1", now::get);
        // (reads, writes, write ms, io ms) at the window's start, 1 s, 2 s and its end at 2.5 s
        long[][] at = {{1000, 5000, 20_000, 1000}, {1100, 5400, 20_250, 1250}, {1100, 6400, 21_250, 2250}, {1101, 6600, 21_300, 2350}};
        long[] clock = {T0, T0 + SECOND, T0 + 2 * SECOND, T0 + 2 * SECOND + SECOND / 2};
        for (int k = 0; k < at.length; k++) {
            Files.writeString(stats, diskstats(at[k][0], at[k][1], at[k][2], at[k][3]));
            now.set(clock[k]);
            sampler.sample();
        }

        JsonObject io = sampler.series();
        assertEquals("nvme0n1", io.getString("device"), io.encode());
        DbSamplerTest.assertDeltas(io, "reads", 4, List.of(100L, 0L, 1L));
        DbSamplerTest.assertDeltas(io, "writes", 4, List.of(400L, 1000L, 200L));
        DbSamplerTest.assertDeltas(io, "writeMs", 4, List.of(250L, 1000L, 50L));
        DbSamplerTest.assertDeltas(io, "ioMs", 4, List.of(250L, 1000L, 100L));
        assertNotNull(io.getJsonArray("utilPercent"), io.encode());
        assertEquals(4, io.getJsonArray("utilPercent").size(), io.encode());
        // 250 ms of 1 s, 1000 ms of 1 s, 100 ms of the last 0.5 s (not of a whole second: that would be 10)
        assertEquals(List.of(25.0, 100.0, 20.0), DbSamplerTest.doubles(io.getJsonArray("utilPercent")).subList(1, 4), io.encode());
    }

    @Test
    void aDeviceTheFileDoesNotListMakesEveryFieldNullWithANoteNamingIt() throws IOException {
        Path stats = dir.resolve("diskstats");
        Files.writeString(stats, diskstats(1, 2, 3, 4));
        AtomicLong now = new AtomicLong(T0);
        DiskSampler sampler = new DiskSampler(stats, "sdz", now::get);
        for (int k = 0; k < 3; k++) {
            now.addAndGet(SECOND);
            sampler.sample();
        }

        JsonObject io = sampler.series();
        for (String field : List.of("reads", "writes", "writeMs", "ioMs", "utilPercent")) DbSamplerTest.assertUnknown(io, field, "sdz");
    }

    @Test
    void anUnreadableFileMakesEveryFieldNullWithANoteNamingTheFile() {
        Path missing = dir.resolve("no-diskstats");
        AtomicLong now = new AtomicLong(T0);
        DiskSampler sampler = new DiskSampler(missing, "nvme0n1", now::get);
        for (int k = 0; k < 3; k++) {
            now.addAndGet(SECOND);
            sampler.sample();
        }

        JsonObject io = sampler.series();
        for (String field : List.of("reads", "writes", "writeMs", "ioMs", "utilPercent")) {
            DbSamplerTest.assertUnknown(io, field, missing.toString());
        }
    }
}
