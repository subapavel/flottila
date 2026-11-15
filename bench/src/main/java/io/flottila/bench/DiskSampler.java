package io.flottila.bench;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The open record's {@code io} object (CORE-15b, {@code --io-device}): the block device holding Postgres' data directory, as
 * {@code /proc/diskstats} names it, read at each backlog sample — reads completed (field 4), writes completed (8), ms writing
 * (11) and ms doing I/O (13), each as the delta since the sample before (the first entry 0, the window's start) — and
 * {@code utilPercent}, the ms doing I/O over the sample's own interval on {@code nanoTime}. The line is the one whose name is
 * the device's exactly (a partition's name starts with it). A sample that cannot be read — no device named, the file
 * unreadable, the device not in it — is null in every field with the reason in {@code notes}, never 0.
 *
 * @since 0.1
 */
final class DiskSampler implements OpenLoop.Sampler {

    /** The counters, in the order of {@link Sample#counters}. */
    static final List<String> COUNTERS = List.of("reads", "writes", "writeMs", "ioMs");

    /** One sample: when it was read and its counters, or why it could not be read ({@code counters} null). */
    private record Sample(long at, long[] counters, String failure) {}

    private final Path diskstats;
    private final String device;
    private final LongSupplier nanoTime;
    private final List<Sample> samples = new ArrayList<>();

    /** Samples {@code device}'s line of {@code diskstats}; {@code device} null: none is named, and nothing is read. */
    DiskSampler(Path diskstats, String device, LongSupplier nanoTime) {
        this.diskstats = diskstats;
        this.device = device;
        this.nanoTime = nanoTime;
    }

    @Override public synchronized void sample() {
        long at = nanoTime.getAsLong();
        if (device == null) {
            samples.add(new Sample(at, null, "no --io-device"));
            return;
        }
        try {
            for (String line : Files.readAllLines(diskstats)) {
                String[] f = line.trim().split("\\s+");
                if (f[2].equals(device)) {
                    samples.add(new Sample(at, new long[] {Long.parseLong(f[3]), Long.parseLong(f[7]), Long.parseLong(f[10]),
                            Long.parseLong(f[12])}, null));
                    return;
                }
            }
            samples.add(new Sample(at, null, "device " + device + " is not in " + diskstats));
        } catch (IOException | RuntimeException unreadable) {
            samples.add(new Sample(at, null, "cannot read " + device + " from " + diskstats + ": " + unreadable));
        }
    }

    @Override public synchronized JsonObject series() {
        JsonObject out = new JsonObject().put("device", device);
        JsonObject notes = new JsonObject();
        List<String> failures = samples.stream().map(Sample::failure).toList();
        for (int c = 0; c < COUNTERS.size(); c++) {
            int counter = c;
            List<Long> values = samples.stream().map(s -> s.counters() == null ? null : s.counters()[counter]).toList();
            Series.put(out, notes, COUNTERS.get(c), Series.deltas(values), failures);
        }
        List<Double> util = new ArrayList<>();
        for (int k = 0; k < samples.size(); k++) {
            Sample now = samples.get(k);
            Sample before = k == 0 ? now : samples.get(k - 1);
            if (now.counters() == null || before.counters() == null) {
                util.add(null);
            } else {
                long interval = now.at() - before.at();
                util.add(k == 0 ? 0.0 : 100.0 * (now.counters()[3] - before.counters()[3]) * 1_000_000 / interval);
            }
        }
        Series.put(out, notes, "utilPercent", util, failures);
        return out.put("notes", notes);
    }
}
