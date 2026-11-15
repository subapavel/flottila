package io.flottila.core.record;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CPU per flow and step from CPU-time samples (F-21, D-094): matches each sample of a virtual thread to the step that ran on that
 * thread when it was taken, and adds up per flow and step the executions, the samples and their CPU. Times are handed in as plain
 * longs (the sampler's stream gives them, see {@code CpuSampler} in the runtime): this class reads no clock and knows no JFR.
 *
 * <p>A virtual thread's sample goes to the innermost already-arrived, still-kept step interval of its thread that contains it —
 * the latest start wins, both ends inclusive — else it waits pending. An arriving step counts one execution, claims its thread's
 * pending samples in {@code [start, end]} (steps arrive in commit order, an inner step before its outer one, so the first step to
 * claim a sample is its innermost) and is kept {@link Limits#lateness()} of stream time after its end for samples that arrive
 * after it. A platform thread's sample counts {@code platform} and is never matched. Eviction is by stream time ({@link #advance},
 * the newest event time the stream has seen), never by the wall clock: a pending sample older than {@link Limits#maxPendingAge()}
 * is {@code unattributed}. Each map is bounded (the caps are over the whole matcher): past {@link Limits#maxPending()} a new
 * sample is {@code dropped}; past {@link Limits#maxRows()} a step of a new (flow, step) has no row and the samples it claims are
 * {@code dropped}; past {@link Limits#maxIntervals()} a new step is not kept for late samples (its execution still counts). Every
 * virtual thread's sample is counted exactly once: {@code samples = attributed + pending + unattributed + dropped}; platform and
 * lost samples are counted apart. A step's CPU is the sum of its samples' own periods, never samples × one period.
 *
 * <p>The recorder hands some events without their thread (FIX-108 review r1): such a step counts its execution, holds no sample and
 * is not kept ({@link #stepWithoutThread}); such a sample counts in {@code samples} and is {@code unattributed} at once
 * ({@link #sampleWithoutThread}), whether its thread was virtual or not being unknown.
 *
 * <p>Thread-safe: the sampler's stream feeds it while the admin API reads it.
 *
 * @since 0.1
 */
public final class StepCpu {

    /**
     * One (flow, step) of the aggregate.
     *
     * @param step the step's number in its flow
     * @param name the name the step last ran under
     * @param executions the step events that arrived
     * @param samples the samples attributed to it
     * @param cpuNanos the sum of those samples' periods
     */
    public record Row(int step, String name, long executions, long samples, long cpuNanos) {}

    /**
     * The matcher's counts. {@code samples = attributed + pending + unattributed + dropped}.
     *
     * @param samples every virtual thread's sample
     * @param attributed the samples a step holds
     * @param pending the samples waiting for their step
     * @param unattributed the samples that waited past {@link Limits#maxPendingAge()}
     * @param platform the platform threads' samples, never matched
     * @param lost the samples the recorder reports lost
     * @param dropped the samples past a cap
     */
    public record Totals(long samples, long attributed, long pending, long unattributed, long platform, long lost, long dropped) {}

    /**
     * How long the matcher keeps what it has not matched yet, and how much of it.
     *
     * @param lateness how long, in stream time after its end, a step is kept for samples that arrive after it
     * @param maxPendingAge how long, in stream time after it was taken, a sample waits for its step
     * @param maxPending the pending samples kept at most
     * @param maxIntervals the steps kept for late samples at most
     * @param maxRows the (flow, step) rows at most
     */
    public record Limits(Duration lateness, Duration maxPendingAge, int maxPending, int maxIntervals, int maxRows) {
        /** Two seconds of lateness, five minutes of pending age, 65 536 pending samples, 131 072 kept steps, 4 096 rows. */
        public static final Limits DEFAULT = new Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 65_536, 131_072, 4_096);

        public Limits {
            Objects.requireNonNull(lateness, "lateness");
            Objects.requireNonNull(maxPendingAge, "maxPendingAge");
        }
    }

    private static final Comparator<Row> BY_CPU = Comparator.comparingLong(Row::cpuNanos).reversed().thenComparingInt(Row::step);

    /** One (flow, step)'s sums. */
    private static final class Sums {
        final int step;
        String name;
        long executions;
        long samples;
        long cpuNanos;

        Sums(int step) {
            this.step = step;
        }
    }

    /** A sample waiting for its step. */
    private record Pending(long at, long period) {}

    /** A step kept for late samples; {@code sums} null when it has no row (past {@link Limits#maxRows()}). */
    private record Kept(long start, long end, Sums sums) {}

    /** One virtual thread's pending samples and kept steps. */
    private static final class Lane {
        final List<Pending> pending = new ArrayList<>(4);
        final List<Kept> kept = new ArrayList<>(4);
    }

    private final Limits limits;
    private final long latenessNanos;
    private final long maxPendingAgeNanos;
    private final Map<Long, Lane> lanes = new HashMap<>();
    private final Map<String, Map<Integer, Sums>> flows = new HashMap<>();
    private int rowCount;
    private int keptCount;
    private long samples;
    private long attributed;
    private long pending;
    private long unattributed;
    private long platform;
    private long lost;
    private long dropped;

    public StepCpu(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.latenessNanos = limits.lateness().toNanos();
        this.maxPendingAgeNanos = limits.maxPendingAge().toNanos();
    }

    /** A CPU-time sample of {@code thread} taken at {@code atNs}, standing for {@code periodNs} of CPU. */
    public synchronized void sample(long thread, boolean virtual, long atNs, long periodNs) {
        if (!virtual) {
            platform++;
            return;
        }
        samples++;
        Lane lane = lanes.get(thread);
        Kept owner = lane == null ? null : innermost(lane.kept, atNs);
        if (owner != null) {
            credit(owner.sums(), periodNs);
            return;
        }
        if (pending >= limits.maxPending()) {
            dropped++;
            return;
        }
        lanes.computeIfAbsent(thread, t -> new Lane()).pending.add(new Pending(atNs, periodNs));
        pending++;
    }

    /** A step of {@code flow} that ran on {@code thread} from {@code startNs} to {@code endNs}: one execution, and the samples it holds. */
    public synchronized void step(long thread, long startNs, long endNs, String flow, int step, String name) {
        Sums sums = executed(flow, step, name);
        Lane lane = lanes.get(thread);
        if (lane != null) {
            for (Iterator<Pending> it = lane.pending.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (startNs <= p.at() && p.at() <= endNs) {
                    it.remove();
                    pending--;
                    credit(sums, p.period());
                }
            }
        }
        if (keptCount < limits.maxIntervals()) {
            lanes.computeIfAbsent(thread, t -> new Lane()).kept.add(new Kept(startNs, endNs, sums));
            keptCount++;
        }
    }

    /** A step of {@code flow} the recorder handed without its thread: one execution; it holds no sample and is not kept. */
    public synchronized void stepWithoutThread(String flow, int step, String name) {
        executed(flow, step, name);
    }

    /** A sample the recorder handed without its thread: counted, and unattributed at once — nothing is known of its thread. */
    public synchronized void sampleWithoutThread() {
        samples++;
        unattributed++;
    }

    /** {@code n} samples the recorder reports lost. */
    public synchronized void lost(long n) {
        lost += n;
    }

    /**
     * The stream has seen events up to {@code watermarkNs}: a step that ended more than the lateness before it is no longer kept,
     * a sample taken more than the max pending age before it and still pending is unattributed.
     */
    public synchronized void advance(long watermarkNs) {
        for (Iterator<Lane> lanesLeft = lanes.values().iterator(); lanesLeft.hasNext(); ) {
            Lane lane = lanesLeft.next();
            for (Iterator<Kept> it = lane.kept.iterator(); it.hasNext(); ) {
                if (watermarkNs - it.next().end() > latenessNanos) {
                    it.remove();
                    keptCount--;
                }
            }
            for (Iterator<Pending> it = lane.pending.iterator(); it.hasNext(); ) {
                if (watermarkNs - it.next().at() > maxPendingAgeNanos) {
                    it.remove();
                    pending--;
                    unattributed++;
                }
            }
            if (lane.pending.isEmpty() && lane.kept.isEmpty()) lanesLeft.remove();
        }
    }

    /** The rows of {@code flow}, by CPU descending, equal CPU by step ascending; none for a flow no step of which arrived. */
    public synchronized List<Row> rows(String flow) {
        List<Row> rows = new ArrayList<>();
        for (Sums s : flows.getOrDefault(flow, Map.of()).values()) rows.add(new Row(s.step, s.name, s.executions, s.samples, s.cpuNanos));
        rows.sort(BY_CPU);
        return List.copyOf(rows);
    }

    /** The lanes held now: one per thread with a pending sample or a kept step. */
    synchronized int lanes() {
        return lanes.size();
    }

    /** The matcher's counts now. */
    public synchronized Totals totals() {
        return new Totals(samples, attributed, pending, unattributed, platform, lost, dropped);
    }

    /** The kept step of the lane holding {@code at} that started last, or null. */
    private static Kept innermost(List<Kept> kept, long at) {
        Kept found = null;
        for (Kept k : kept) {
            if (k.start() <= at && at <= k.end() && (found == null || k.start() > found.start())) found = k;
        }
        return found;
    }

    /** One execution of (flow, step) under {@code name}: its sums, created within {@link Limits#maxRows()}; null past it. */
    private Sums executed(String flow, int step, String name) {
        Map<Integer, Sums> steps = flows.get(flow);
        Sums sums = steps == null ? null : steps.get(step);
        if (sums == null && rowCount < limits.maxRows()) {
            sums = new Sums(step);
            flows.computeIfAbsent(flow, f -> new HashMap<>()).put(step, sums);   // no flow's map without a row in it
            rowCount++;
        }
        if (sums != null) {
            sums.name = name;
            sums.executions++;
        }
        return sums;
    }

    /** One sample of {@code periodNs} to {@code sums}; dropped when the step has no row. */
    private void credit(Sums sums, long periodNs) {
        if (sums == null) {
            dropped++;
            return;
        }
        attributed++;
        sums.samples++;
        sums.cpuNanos += periodNs;
    }
}
