package io.flottila.core.record;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-108 (D-094), jqwik: whatever arrives — samples of virtual and platform threads, steps, lost counts, stream-time advances, under
 * caps small enough to overflow — every virtual thread's sample is counted exactly once ({@code samples = attributed + pending +
 * unattributed + dropped}, platform and lost apart, the rows' samples summing to {@code attributed}); and for any tree of nested and
 * sibling steps per thread, arriving in commit order (a step when it ends, an inner step before its outer one) with the samples
 * anywhere among them, each sample goes to the innermost step of its thread containing it (latest start, both ends inclusive) — the
 * oracle below — and one outside every step waits pending until it ages out unattributed.
 *
 * <p>Named {@code *PropertyTest}, not {@code StepCpuProperties} as the spec wrote it: Surefire's default includes would never run the latter.
 */
class StepCpuPropertyTest {

    static final String[] FLOWS = {"a", "b"};

    /** One call on the matcher, as the sampler's stream makes it. */
    sealed interface Op permits Sample, Step, Lost, Advance {}

    record Sample(long thread, boolean virtual, long at, long period) implements Op {}

    record Step(long thread, long start, long end, String flow, int step) implements Op {
        String name() { return "s" + step; }
    }

    record Lost(long n) implements Op {}

    record Advance(long watermark) implements Op {}

    static void apply(StepCpu cpu, Op op) {
        switch (op) {
            case Sample s -> cpu.sample(s.thread(), s.virtual(), s.at(), s.period());
            case Step s -> cpu.step(s.thread(), s.start(), s.end(), s.flow(), s.step(), s.name());
            case Lost l -> cpu.lost(l.n());
            case Advance a -> cpu.advance(a.watermark());
        }
    }

    // --- everySampleIsCountedExactlyOnce

    @Property(tries = 400)
    void everySampleIsCountedExactlyOnce(@ForAll long seed) {
        Random r = new Random(seed);
        StepCpu.Limits limits = new StepCpu.Limits(Duration.ofNanos(1 + r.nextInt(40)), Duration.ofNanos(1 + r.nextInt(80)),
                1 + r.nextInt(4), 1 + r.nextInt(4), 1 + r.nextInt(4));
        StepCpu cpu = new StepCpu(limits);
        long virtualSamples = 0;
        long platformSamples = 0;
        long lost = 0;
        long minPeriod = Long.MAX_VALUE;
        long maxPeriod = 0;
        long watermark = 0;
        StepCpu.Totals before = cpu.totals();
        assertEquals(new StepCpu.Totals(0, 0, 0, 0, 0, 0, 0), before, "a new matcher has counted nothing");
        int ops = 1 + r.nextInt(80);
        List<Op> done = new ArrayList<>();
        for (int i = 0; i < ops; i++) {
            Op op;
            int kind = r.nextInt(100);
            long thread = 1 + r.nextInt(3);
            if (kind < 50) {
                boolean virtual = r.nextInt(5) > 0;
                long period = 1 + r.nextInt(9);
                op = new Sample(thread, virtual, r.nextInt(300), period);
                if (virtual) {
                    virtualSamples++;
                    minPeriod = Math.min(minPeriod, period);
                    maxPeriod = Math.max(maxPeriod, period);
                } else {
                    platformSamples++;
                }
            } else if (kind < 80) {
                long start = r.nextInt(300);
                op = new Step(thread, start, start + r.nextInt(40), FLOWS[r.nextInt(2)], 1 + r.nextInt(3));
            } else if (kind < 88) {
                long n = r.nextInt(5);
                op = new Lost(n);
                lost += n;
            } else {
                watermark += r.nextInt(60);   // stream time never goes back
                op = new Advance(watermark);
            }
            apply(cpu, op);
            done.add(op);
            StepCpu.Totals t = cpu.totals();
            String where = "after " + done + " under " + limits + ": " + t;
            assertEquals(virtualSamples, t.samples(), "samples counts every virtual thread's sample, " + where);
            assertEquals(platformSamples, t.platform(), "platform counts every platform thread's sample, " + where);
            assertEquals(lost, t.lost(), "lost adds up, " + where);
            assertEquals(t.samples(), t.attributed() + t.pending() + t.unattributed() + t.dropped(), "each sample counted exactly once, " + where);
            assertTrue(t.pending() <= limits.maxPending(), "pending stays within maxPending, " + where);
            assertTrue(t.attributed() >= before.attributed() && t.unattributed() >= before.unattributed() && t.dropped() >= before.dropped(),
                    "attributed, unattributed and dropped never go back, " + where + " (was " + before + ")");
            List<StepCpu.Row> rows = new ArrayList<>();
            for (String flow : FLOWS) rows.addAll(cpu.rows(flow));
            assertTrue(rows.size() <= limits.maxRows(), "rows stay within maxRows, " + where + ", rows " + rows);
            long rowSamples = rows.stream().mapToLong(StepCpu.Row::samples).sum();
            long rowCpu = rows.stream().mapToLong(StepCpu.Row::cpuNanos).sum();
            assertEquals(t.attributed(), rowSamples, "the rows hold exactly the attributed samples, " + where + ", rows " + rows);
            if (rowSamples > 0) {
                assertTrue(rowCpu >= rowSamples * minPeriod && rowCpu <= rowSamples * maxPeriod,
                        "the rows' CPU is a sum of the attributed samples' periods, " + where + ", rows " + rows);
            } else {
                assertEquals(0, rowCpu, "no attributed sample, no CPU, " + where + ", rows " + rows);
            }
            before = t;
        }
    }

    // --- attributionMatchesTheInnermostOracleForAnyCommitOrderedArrival

    /** The steps each thread ran (trees of nested and sibling steps, no two starting together) and every sample, in arrival order. */
    record Scenario(List<Step> steps, List<Sample> samples, List<Op> arrivals, long lost) {}

    @Property(tries = 400)
    void attributionMatchesTheInnermostOracleForAnyCommitOrderedArrival(@ForAll long seed) {
        Scenario s = scenario(new Random(seed));
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);   // nothing is evicted or capped at these sizes and times
        for (Op op : s.arrivals()) apply(cpu, op);

        // the oracle: each virtual sample's innermost containing step of its thread, latest start, both ends inclusive
        Map<String, Map<Integer, long[]>> expected = new HashMap<>();   // flow -> step -> {executions, samples, cpu}
        Map<String, Map<Integer, String>> names = new HashMap<>();
        for (Step step : s.steps()) {
            expected.computeIfAbsent(step.flow(), f -> new HashMap<>()).computeIfAbsent(step.step(), n -> new long[3])[0]++;
            names.computeIfAbsent(step.flow(), f -> new HashMap<>()).put(step.step(), step.name());
        }
        long virtual = 0;
        long platform = 0;
        long matched = 0;
        for (Sample sample : s.samples()) {
            if (!sample.virtual()) {
                platform++;
                continue;
            }
            virtual++;
            Step innermost = null;
            for (Step step : s.steps()) {
                if (step.thread() == sample.thread() && step.start() <= sample.at() && sample.at() <= step.end()
                        && (innermost == null || step.start() > innermost.start())) innermost = step;
            }
            if (innermost == null) continue;
            matched++;
            long[] row = expected.get(innermost.flow()).get(innermost.step());
            row[1]++;
            row[2] += sample.period();
        }
        String where = "seed scenario " + s;
        for (String flow : FLOWS) {
            List<StepCpu.Row> rows = new ArrayList<>();
            expected.getOrDefault(flow, Map.of()).forEach((step, v) -> rows.add(new StepCpu.Row(step, names.get(flow).get(step), v[0], v[1], v[2])));
            rows.sort(Comparator.comparingLong(StepCpu.Row::cpuNanos).reversed().thenComparingInt(StepCpu.Row::step));
            assertEquals(rows, cpu.rows(flow), "flow " + flow + " by the innermost oracle, " + where);
        }
        assertEquals(new StepCpu.Totals(virtual, matched, virtual - matched, 0, platform, s.lost(), 0), cpu.totals(),
                "a sample outside every step waits pending, " + where);

        long last = s.arrivals().stream().mapToLong(op -> op instanceof Step st ? st.end() : op instanceof Sample sa ? sa.at() : 0).max().orElse(0);
        cpu.advance(last + Duration.ofHours(1).toNanos());
        assertEquals(new StepCpu.Totals(virtual, matched, 0, virtual - matched, platform, s.lost(), 0), cpu.totals(),
                "an hour of stream time later every pending sample is unattributed, " + where);
    }

    /**
     * One to three threads, each running one to three messages one after another; a message is a tree of steps: step 1 holds step 2
     * (a stage wraps the rest of the pipeline), and so on to depth 3, with up to two siblings (a split hands each part on). A child
     * starts after its parent and ends before it, siblings and messages leave a gap — no two steps of a thread start or end together.
     * Samples land anywhere on the thread's time line, often exactly on or next to a step's start or end; a few are platform samples,
     * some on a virtual thread's id. Arrival: the steps by end (an inner step commits first), every sample and lost count at a random
     * place among them.
     */
    static Scenario scenario(Random r) {
        List<Step> steps = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        int threads = 1 + r.nextInt(3);
        for (int t = 0; t < threads; t++) {
            long thread = 100 + t;
            long clock = r.nextInt(5);
            List<Step> mine = new ArrayList<>();
            int messages = 1 + r.nextInt(3);
            for (int m = 0; m < messages; m++) {
                String flow = FLOWS[r.nextInt(2)];
                long end = tree(r, thread, flow, 1, clock + 1 + r.nextInt(5), mine);
                clock = end + 1 + r.nextInt(3);
            }
            steps.addAll(mine);
            int n = r.nextInt(25);
            for (int i = 0; i < n; i++) {
                long at;
                if (!mine.isEmpty() && r.nextInt(10) < 4) {
                    Step near = mine.get(r.nextInt(mine.size()));
                    long[] edges = {near.start(), near.end(), near.start() - 1, near.end() + 1};
                    at = edges[r.nextInt(edges.length)];
                } else {
                    at = r.nextInt((int) clock + 3);
                }
                samples.add(new Sample(thread, r.nextInt(8) > 0, at, 1_000L * (1 + r.nextInt(9))));
            }
        }
        int strays = r.nextInt(3);
        for (int i = 0; i < strays; i++) samples.add(new Sample(900 + i, false, r.nextInt(60), 1_000L));
        List<Op> arrivals = new ArrayList<>(steps.stream().sorted(Comparator.comparingLong(Step::end).thenComparingLong(Step::thread)).toList());
        for (Sample sample : samples) arrivals.add(r.nextInt(arrivals.size() + 1), sample);
        long lost = 0;
        int losses = r.nextInt(3);
        for (int i = 0; i < losses; i++) {
            long n = 1 + r.nextInt(4);
            lost += n;
            arrivals.add(r.nextInt(arrivals.size() + 1), new Lost(n));
        }
        return new Scenario(List.copyOf(steps), List.copyOf(samples), List.copyOf(arrivals), lost);
    }

    /** The step at {@code depth} starting at {@code start} with its children; adds them all to {@code into}, returns its end. */
    static long tree(Random r, long thread, String flow, int depth, long start, List<Step> into) {
        long cursor = start + 1 + r.nextInt(3);
        int roll = r.nextInt(100);
        int children = depth >= 3 ? 0 : roll < 40 ? 0 : roll < 85 ? 1 : 2;
        for (int c = 0; c < children; c++) {
            long childEnd = tree(r, thread, flow, depth + 1, cursor, into);
            cursor = childEnd + 1 + r.nextInt(3);
        }
        long end = cursor + r.nextInt(3);
        into.add(new Step(thread, start, end, flow, depth));
        return end;
    }
}
