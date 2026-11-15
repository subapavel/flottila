package io.flottila.bench;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The cumulative CPU of a process outside the bench JVM (CORE-15 {@code --pg-cpu-source}): a cgroup v2 directory, read from
 * its {@code cpu.stat} {@code usage_usec}, or a pid (all digits), read from {@code /proc/<pid>/stat} utime + stime in clock
 * ticks of USER_HZ 100 (the kernel's fixed user-space tick on every Linux architecture the bench runs on). Each
 * {@link #read()} reads the file again. CORE-15b: a pid under a proc root ({@link #of(String, Path)}) is a Postgres cluster's
 * postmaster, read with every process whose parent it is.
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

    /** The file a reading names: the cgroup's cpu.stat, the process' (or the postmaster's) stat. */
    final Path file;

    private CpuSource(Path file) {
        this.file = file;
    }

    /** {@code source} as a pid when it is all digits, else as a cgroup v2 directory. */
    static CpuSource of(String source) {
        return source.matches("[0-9]+") ? new Pid(Path.of("/proc", source, "stat")) : new Cgroup(Path.of(source, "cpu.stat"));
    }

    /**
     * {@code source} as a cgroup v2 directory, or, when it is all digits, as the pid of a Postgres cluster's postmaster under
     * the proc root {@code proc} ({@link Cluster}): the CPU of the whole cluster.
     */
    static CpuSource of(String source, Path proc) {
        return source.matches("[0-9]+") ? new Cluster(proc, Long.parseLong(source)) : new Cgroup(Path.of(source, "cpu.stat"));
    }

    /** The CPU so far, or the reason it cannot be read, naming the file read. */
    Reading read() {
        try {
            return new Reading(nanos(), null);
        } catch (IOException | RuntimeException unreadable) {
            return new Reading(0, "cannot read the CPU of " + file + ": " + unreadable);
        }
    }

    /** The cumulative CPU in nanoseconds, read now; a file without it throws. */
    abstract long nanos() throws IOException;

    /**
     * The open record's per-sample series of {@code source} (CORE-15b, {@code pgCpu}): {@code cpuMs}, the CPU in milliseconds
     * spent since the sample before (the first entry 0, the window's start), null for a sample whose reading (or the one
     * before it) failed, with the first failure in {@code notes.cpuMs}; JSON null when no sample was read.
     */
    static OpenLoop.Sampler sampler(Supplier<Reading> source) {
        return new OpenLoop.Sampler() {
            private final List<Long> nanos = new ArrayList<>();
            private final List<String> failures = new ArrayList<>();

            @Override public synchronized void sample() {
                Reading r = source.get();
                nanos.add(r.failure() == null ? r.nanos() : null);
                failures.add(r.failure());
            }

            @Override public synchronized JsonObject series() {
                List<Double> ms = Series.deltas(nanos).stream().map(d -> d == null ? null : d / 1_000_000.0).toList();
                JsonObject out = new JsonObject();
                JsonObject notes = new JsonObject();
                Series.put(out, notes, "cpuMs", ms, failures);
                return out.put("notes", notes);
            }
        };
    }

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

        @Override long nanos() throws IOException {
            for (String line : Files.readString(file).split("\n")) {
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

        @Override long nanos() throws IOException {
            String[] fields = fields(Files.readString(file));
            return (Long.parseLong(fields[11]) + Long.parseLong(fields[12])) * TICK_NANOS;
        }

        /** The stat's fields from field 3 (the state) on: those after the command's closing parenthesis (a command may hold one). */
        static String[] fields(String content) {
            return content.substring(content.lastIndexOf(')') + 2).split(" ");
        }
    }

    /**
     * A Postgres cluster under a proc root (CORE-15b): utime + stime + cutime + cstime (fields 14 to 17) of its postmaster and
     * of every process whose parent (field 4) it is — its backends, checkpointer, walwriter, archiver and the rest. The c-times
     * are the CPU of the children a process waited for: the postmaster's hold its exited backends, a child's what it ran and
     * reaped (an archive_command); the kernel moves an exiting child's four into its parent's c-times, so nothing is counted
     * twice. The postmaster is read first: a child that exits after it is missed in this reading and counted in the next
     * one's, never twice; a child whose stat is gone by the time it is read (it exited mid-read) is left out. Only the
     * postmaster's own stat being unreadable makes the reading fail.
     */
    static final class Cluster extends CpuSource {
        private final Path proc;
        private final long postmaster;

        Cluster(Path proc, long postmaster) {
            super(proc.resolve(String.valueOf(postmaster)).resolve("stat"));
            this.proc = proc;
            this.postmaster = postmaster;
        }

        @Override long nanos() throws IOException {
            long ticks = ticks(Pid.fields(Files.readString(file)));
            try (DirectoryStream<Path> processes = Files.newDirectoryStream(proc, p -> p.getFileName().toString().matches("[0-9]+"))) {
                for (Path process : processes) {
                    String[] fields;
                    try {
                        fields = Pid.fields(Files.readString(process.resolve("stat")));
                    } catch (IOException exited) {   // listed, then gone: a backend that exited while the tree was read
                        continue;
                    }
                    if (Long.parseLong(fields[1]) == postmaster) ticks += ticks(fields);
                }
            }
            return ticks * TICK_NANOS;
        }

        /** utime + stime + cutime + cstime of one stat's fields ({@link Pid#fields}). */
        private static long ticks(String[] fields) {
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]) + Long.parseLong(fields[13]) + Long.parseLong(fields[14]);
        }
    }
}
