package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b, the implementer's cases on {@link DiskSampler}: every series starts at 0 (decision 1), a device that shows up only
 * after the window's start is null until there is a sample before to subtract, a garbled line is unreadable naming the file,
 * and no {@code --io-device} reads nothing and says so.
 */
class DiskSamplerCasesTest {

    @TempDir Path dir;

    @Test
    void aDeviceSeenFromTheSecondSampleOnIsNullUntilItHasASampleBeforeAndEverySeriesStartsAtZeroWhenKnown() throws IOException {
        Path stats = dir.resolve("diskstats");
        AtomicLong now = new AtomicLong(DiskSamplerTest.T0);
        DiskSampler sampler = new DiskSampler(stats, "nvme0n1", now::get);
        Files.writeString(stats, "   8       0 sda 11 0 88 5 22 0 176 9 0 13 14 0 0 0 0 0 0\n");
        sampler.sample();
        long[][] at = {{10, 20, 30, 40}, {15, 30, 60, 540}};
        for (long[] c : at) {
            now.addAndGet(DiskSamplerTest.SECOND);
            Files.writeString(stats, DiskSamplerTest.diskstats(c[0], c[1], c[2], c[3]));
            sampler.sample();
        }

        JsonObject io = sampler.series();
        assertEquals(Arrays.asList(null, null, 5L), DbSamplerTest.longs(io.getJsonArray("reads")), io.encode());
        assertEquals(Arrays.asList(null, null, 500L), DbSamplerTest.longs(io.getJsonArray("ioMs")), io.encode());
        assertEquals(Arrays.asList(null, null, 50.0), DbSamplerTest.doubles(io.getJsonArray("utilPercent")), io.encode());
        assertTrue(io.getJsonObject("notes").getString("reads").contains("nvme0n1"), io.encode());

        DiskSampler fresh = new DiskSampler(stats, "nvme0n1", now::get);
        fresh.sample();
        JsonObject first = fresh.series();
        for (String field : List.of("reads", "writes", "writeMs", "ioMs")) {
            assertEquals(List.of(0L), DbSamplerTest.longs(first.getJsonArray(field)), field + " at the window's start: " + first.encode());
        }
        assertEquals(List.of(0.0), DbSamplerTest.doubles(first.getJsonArray("utilPercent")), first.encode());
    }

    @Test
    void aGarbledLineIsUnreadableNamingTheFile() throws IOException {
        Path stats = dir.resolve("diskstats");
        Files.writeString(stats, " 259       0 nvme0n1 many 3 8000 400 9 9 64000 7 0 4 99999\n");
        DiskSampler sampler = new DiskSampler(stats, "nvme0n1", () -> DiskSamplerTest.T0);
        sampler.sample();

        DbSamplerTest.assertUnknown(sampler.series(), "reads", stats.toString());
    }

    @Test
    void noIoDeviceReadsNothingAndSaysSo() {
        DiskSampler sampler = new DiskSampler(dir.resolve("never-read"), null, () -> DiskSamplerTest.T0);
        sampler.sample();
        sampler.sample();

        JsonObject io = sampler.series();
        assertNull(io.getValue("device"), io.encode());
        for (String field : List.of("reads", "writes", "writeMs", "ioMs", "utilPercent")) DbSamplerTest.assertUnknown(io, field, "no --io-device");
    }
}
