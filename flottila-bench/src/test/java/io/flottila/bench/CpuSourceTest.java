package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15: the CPU of a process outside the bench JVM over the window ({@code --pg-cpu-source}): a cgroup v2 directory (its
 * {@code cpu.stat} {@code usage_usec}) or a pid ({@code /proc/<pid>/stat} utime + stime, in clock ticks). A reading is the
 * cumulative CPU in nanoseconds or the reason it could not be read; the window's figure goes into the record's {@code cpuMs}
 * as milliseconds, or as JSON null with a {@code cpuNotes} reason when either end was unreadable — never 0.
 */
class CpuSourceTest {

    @TempDir Path dir;

    @Test
    void aCgroupsUsageIsReadInMicrosecondsAndTheWindowsDeltaRecordedInMilliseconds() throws IOException {
        Path cgroup = Files.createDirectories(dir.resolve("postgresql.service"));
        cpuStat(cgroup, 1_500_000);
        CpuSource source = CpuSource.of(cgroup.toString());

        CpuSource.Reading before = source.read();
        assertNull(before.failure(), "read: " + before);
        assertEquals(1_500_000_000L, before.nanos(), "usage_usec 1 500 000 µs = 1.5 s");

        cpuStat(cgroup, 1_750_000);
        CpuSource.Reading after = source.read();
        assertEquals(1_750_000_000L, after.nanos(), "read again, not cached");

        JsonObject open = new JsonObject();
        CpuSource.put(open, "postgres", before, after);
        assertEquals(250.0, open.getJsonObject("cpuMs").getNumber("postgres").doubleValue(), 1e-9, "250 000 µs: " + open.encode());
        JsonObject notes = open.getJsonObject("cpuNotes");
        assertTrue(notes == null || !notes.containsKey("postgres"), "a measured field has no note: " + open.encode());
    }

    @Test
    void aMissingCgroupIsUnreadableAndItsFieldNullWithAReasonNeverZero() {
        Path missing = dir.resolve("no-such.service");
        CpuSource.Reading r = CpuSource.of(missing.toString()).read();
        assertNotNull(r.failure(), "a missing cgroup is unreadable");
        assertTrue(r.failure().contains(missing.toString()), "the reason names the source: " + r.failure());

        JsonObject open = new JsonObject();
        CpuSource.put(open, "postgres", r, r);
        assertUnmeasured(open, "postgres");
        assertTrue(open.getJsonObject("cpuNotes").getString("postgres").contains(missing.toString()), open.encode());
    }

    @Test
    void aCpuStatWithoutUsageIsUnreadable() throws IOException {
        Path cgroup = Files.createDirectories(dir.resolve("garbled.service"));
        Files.writeString(cgroup.resolve("cpu.stat"), "nr_periods 0\nnr_throttled 0\n");
        CpuSource.Reading r = CpuSource.of(cgroup.toString()).read();
        assertNotNull(r.failure(), "no usage_usec line");
        assertTrue(r.failure().contains("usage_usec"), "the reason names what is missing: " + r.failure());
    }

    @Test
    void anEndThatBecameUnreadableMakesTheWindowNullWithTheReason() throws IOException {
        Path cgroup = Files.createDirectories(dir.resolve("stopped.service"));
        cpuStat(cgroup, 2_000_000);
        CpuSource source = CpuSource.of(cgroup.toString());
        CpuSource.Reading before = source.read();
        Files.delete(cgroup.resolve("cpu.stat"));   // the service stopped during the window
        CpuSource.Reading after = source.read();
        assertNotNull(after.failure());

        JsonObject open = new JsonObject();
        CpuSource.put(open, "postgres", before, after);
        assertUnmeasured(open, "postgres");

        JsonObject reversed = new JsonObject();
        CpuSource.put(reversed, "postgres", after, before);
        assertUnmeasured(reversed, "postgres");
    }

    @Test
    void aPidsCpuIsItsUserAndSystemTicksInNanoseconds() {
        String self = String.valueOf(ProcessHandle.current().pid());
        CpuSource source = CpuSource.of(self);
        CpuSource.Reading first = source.read();
        long reference = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
        CpuSource.Reading second = source.read();
        assertNull(first.failure(), "this JVM's /proc stat is readable: " + first);
        assertNull(second.failure(), String.valueOf(second));
        // clock ticks are 10 ms at USER_HZ 100: the reads bracket the JDK's own figure within a few ticks, at the same unit
        long slack = Duration.ofMillis(50).toNanos();
        assertTrue(first.nanos() <= reference + slack && reference <= second.nanos() + slack,
                "ticks converted to nanoseconds: " + first.nanos() + " ≤ " + reference + " ≤ " + second.nanos() + " (± 50 ms)");
        assertTrue(first.nanos() > slack, "a test JVM has used well over 50 ms of CPU by now: " + first.nanos());
    }

    @Test
    void aPidThatDoesNotExistIsUnreadableNamingIt() {
        CpuSource.Reading r = CpuSource.of("999999999").read();
        assertNotNull(r.failure(), "no such process");
        assertTrue(r.failure().contains("999999999"), "the reason names the pid: " + r.failure());
    }

    // --- fixtures

    static void cpuStat(Path cgroup, long usageMicros) throws IOException {
        Files.writeString(cgroup.resolve("cpu.stat"), "usage_usec " + usageMicros + "\nuser_usec " + usageMicros * 2 / 3
                + "\nsystem_usec " + (usageMicros - usageMicros * 2 / 3) + "\nnr_periods 0\nnr_throttled 0\nthrottled_usec 0\n");
    }

    /** {@code field} is present in {@code cpuMs} as JSON null (not 0, not absent) and {@code cpuNotes} says why. */
    static void assertUnmeasured(JsonObject open, String field) {
        JsonObject cpu = open.getJsonObject("cpuMs");
        assertNotNull(cpu, open.encode());
        assertTrue(cpu.containsKey(field), "the field is in the record: " + open.encode());
        assertNull(cpu.getValue(field), "unknown is null, never 0: " + open.encode());
        JsonObject notes = open.getJsonObject("cpuNotes");
        assertNotNull(notes, "an unmeasured field has a note: " + open.encode());
        assertFalse(notes.getString(field, "").isBlank(), "the note gives the reason: " + open.encode());
    }
}
