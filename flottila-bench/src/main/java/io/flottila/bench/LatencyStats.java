package io.flottila.bench;

import java.util.Arrays;

/**
 * Fixed-capacity latency samples in nanoseconds with nearest-rank percentiles — what
 * bench/RESULTS.md reports. Thread-safe recording, sorted once on first read.
 *
 * @since 0.1
 */
public final class LatencyStats {

    private final long[] samples;
    private int count;
    private boolean sorted;

    public LatencyStats(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
        this.samples = new long[capacity];
    }

    public synchronized void record(long nanos) {
        if (count == samples.length) throw new IllegalStateException("more samples than planned: " + samples.length);
        samples[count++] = nanos;
        sorted = false;
    }

    public synchronized int count() { return count; }

    /** Nearest-rank percentile (0..100) in milliseconds. */
    public synchronized double percentileMillis(int percentile) {
        sort();
        int rank = (int) Math.ceil(percentile / 100.0 * count);
        int index = Math.max(0, Math.min(count - 1, rank - 1));
        return samples[index] / 1_000_000.0;
    }

    public synchronized double maxMillis() {
        sort();
        return samples[count - 1] / 1_000_000.0;
    }

    public synchronized double meanMillis() {
        if (count == 0) throw new IllegalStateException("no samples");
        double sum = 0;
        for (int i = 0; i < count; i++) sum += samples[i];
        return sum / count / 1_000_000.0;
    }

    private void sort() {
        if (count == 0) throw new IllegalStateException("no samples");
        if (!sorted) { Arrays.sort(samples, 0, count); sorted = true; }
    }
}
