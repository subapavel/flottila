package io.flottila.bench;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The cumulative CPU of a process outside the bench JVM (CORE-15 {@code --pg-cpu-source}): a cgroup v2 directory, read from
 * its {@code cpu.stat} {@code usage_usec}, or a pid (all digits), read from {@code /proc/<pid>/stat} utime + stime in clock
 * ticks of USER_HZ 100 (the kernel's fixed user-space tick on every Linux architecture the bench runs on). Each
 * {@link #read()} reads the file again.
 *
 * @since 0.1
 */
abstract sealed class CpuSource {

    /**
     * One reading: the cumulative CPU in nanoseconds, or why it could not be read ({@code failure} non-null, {@code nanos} 0).
     *
     * @param nanos the CPU so far, in nanoseconds
     * @param failure null, or the reason the source was unreadable
     */
    record Reading(long nanos, String failure) {}

    /** Nanoseconds of one USER_HZ clock tick. */
    static final long TICK_NANOS = 10_000_000L;

    private final Path file;

    private CpuSource(Path file) {
        this.file = file;
    }

    /** {@code source} as a pid when it is all digits, else as a cgroup v2 directory. */
    static CpuSource of(String source) {
        return source.matches("[0-9]+") ? new Pid(Path.of("/proc", source, "stat")) : new Cgroup(Path.of(source, "cpu.stat"));
    }

    /** The CPU so far, or the reason it cannot be read, naming the file read. */
    Reading read() {
        try {
            return new Reading(nanos(Files.readString(file)), null);
        } catch (IOException | RuntimeException unreadable) {
            return new Reading(0, "cannot read the CPU of " + file + ": " + unreadable);
        }
    }

    /** The cumulative CPU in nanoseconds from the file's {@code content}; a content without it throws. */
    abstract long nanos(String content);

    /**
     * Puts the CPU between {@code before} and {@code after} into {@code open}'s {@code cpuMs.<field>} in milliseconds, or JSON
     * null with the first unreadable end's reason in {@code cpuNotes.<field>}: an unknown figure is never 0.
     */
    static void put(JsonObject open, String field, Reading before, Reading after) {
        JsonObject cpu = open.getJsonObject("cpuMs", new JsonObject());
        JsonObject notes = open.getJsonObject("cpuNotes", new JsonObject());
        open.put("cpuMs", cpu).put("cpuNotes", notes);
        String failure = before.failure() != null ? before.failure() : after.failure();
        if (failure == null) {
            cpu.put(field, (after.nanos() - before.nanos()) / 1_000_000.0);
        } else {
            cpu.putNull(field);
            notes.put(field, failure);
        }
    }

    /** A cgroup v2 directory's {@code cpu.stat}. */
    static final class Cgroup extends CpuSource {
        Cgroup(Path file) {
            super(file);
        }

        @Override long nanos(String content) {
            for (String line : content.split("\n")) {
                if (line.startsWith("usage_usec ")) return Long.parseLong(line.substring("usage_usec ".length()).trim()) * 1000;
            }
            throw new IllegalStateException("no usage_usec line");
        }
    }

    /** A process' {@code /proc/<pid>/stat}: fields 14 and 15 (utime, stime), counted after the command's closing parenthesis. */
    static final class Pid extends CpuSource {
        Pid(Path file) {
            super(file);
        }

        @Override long nanos(String content) {
            String[] fields = content.substring(content.lastIndexOf(')') + 2).split(" ");   // fields[0] is field 3, the state
            return (Long.parseLong(fields[11]) + Long.parseLong(fields[12])) * TICK_NANOS;
        }
    }
}
