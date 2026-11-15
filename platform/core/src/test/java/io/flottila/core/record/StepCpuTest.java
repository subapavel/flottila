package io.flottila.core.record;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-108 (D-094): {@link StepCpu} matches JFR CPU-time samples to step events by thread and time, with timestamps handed in as
 * plain longs — no clock, no JFR. A virtual thread's sample goes to the innermost already-arrived, still-kept step interval of its
 * thread containing it (latest start wins, both ends inclusive), else waits pending; an arriving step claims its thread's pending
 * samples in {@code [start, end]}, counts one execution and is kept {@code lateness} for late samples; a platform thread's sample
 * counts {@code platform} and is never matched; eviction is by stream time ({@code advance}); a pending sample past its age is
 * {@code unattributed}; past a cap a new entry is {@code dropped}. CPU is the sum of each sample's own period.
 *
 * <p>The shape these tests assume (FIX-108 test-author): {@code new StepCpu(StepCpu.Limits)}; {@code sample(long thread, boolean
 * virtual, long atNs, long periodNs)}; {@code step(long thread, long startNs, long endNs, String flow, int step, String name)};
 * {@code lost(long n)}; {@code advance(long watermarkNs)}; {@code List<StepCpu.Row> rows(String flow)}; {@code StepCpu.Totals
 * totals()}; nested records {@code Row(int step, String name, long executions, long samples, long cpuNanos)}, {@code Totals(long
 * samples, long attributed, long pending, long unattributed, long platform, long lost, long dropped)} — {@code samples} counts the
 * virtual threads' samples only, platform ones are counted apart — and {@code Limits(Duration lateness, Duration maxPendingAge, int
 * maxPending, int maxIntervals, int maxRows)} with {@code Limits.DEFAULT}; round 3 (review r1): a package-private {@code int lanes()},
 * the threads the matcher holds a lane for now.
 */
class StepCpuTest {

    /** Stream time of the cases: 10 s after the epoch of the ticks, so a step's start minus one is still positive. */
    static final long T = 10_000_000_000L;
    static final long MS = 1_000_000L;
    /** A sample's period in most cases: 4 ms. */
    static final long P = 4 * MS;
    static final long VT = 41;
    static final long VT2 = 42;

    final StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);

    static StepCpu.Totals totals(long samples, long attributed, long pending, long unattributed, long platform, long lost, long dropped) {
        return new StepCpu.Totals(samples, attributed, pending, unattributed, platform, lost, dropped);
    }

    @Test
    void defaultLimitsKeepTwoSecondsOfLatenessFiveMinutesOfPendingAndTheSpecsCaps() {
        assertEquals(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 65_536, 131_072, 4_096), StepCpu.Limits.DEFAULT,
                "lateness 2 s, max pending age 5 min, 65 536 pending samples, 131 072 kept intervals, 4 096 rows");
    }

    /**
     * Step 2 runs inside step 1 on one thread (a stage wraps the rest of the pipeline). Samples inside step 2 go to step 2 whether
     * they wait pending for it or arrive after both steps (then the latest start wins); samples only inside step 1 go to step 1.
     */
    @Test
    void aSampleGoesToTheInnermostStepContainingIt() {
        cpu.sample(VT, true, T + 50 * MS, P);   // inside inner and outer, pending
        cpu.sample(VT, true, T + 80 * MS, P);   // inside outer only, pending
        cpu.step(VT, T + 40 * MS, T + 60 * MS, "f", 2, "inner");   // the inner step commits first
        cpu.step(VT, T, T + 100 * MS, "f", 1, "outer");
        cpu.sample(VT, true, T + 45 * MS, P);   // late, inside both: the latest start is inner's
        cpu.sample(VT, true, T + 20 * MS, P);   // late, inside outer only
        cpu.sample(VT, true, T + 55 * MS, P);   // late, inside both

        assertEquals(List.of(new StepCpu.Row(2, "inner", 1, 3, 3 * P), new StepCpu.Row(1, "outer", 1, 2, 2 * P)), cpu.rows("f"),
                "inner: the pending sample at 50 ms and the late ones at 45 and 55 ms; outer: 80 ms (pending) and 20 ms (late)");
        assertEquals(totals(5, 5, 0, 0, 0, 0, 0), cpu.totals());
    }

    /** A sample that arrives before its step waits; the step claims only its own thread's pending samples, each step counts one execution. */
    @Test
    void aPendingSampleIsClaimedWhenItsStepArrives() {
        cpu.sample(VT, true, T + 10 * MS, P);
        cpu.sample(VT2, true, T + 10 * MS, P);   // same time, another thread
        assertEquals(totals(2, 0, 2, 0, 0, 0, 0), cpu.totals(), "both wait for a step of their thread");
        assertEquals(List.of(), cpu.rows("f"), "no step arrived: no row");

        cpu.step(VT, T, T + 20 * MS, "f", 1, "a");
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 1, P)), cpu.rows("f"), "the step claims its thread's sample, not the other thread's");
        assertEquals(totals(2, 1, 1, 0, 0, 0, 0), cpu.totals());

        cpu.step(VT2, T, T + 20 * MS, "f", 1, "a");
        assertEquals(List.of(new StepCpu.Row(1, "a", 2, 2, 2 * P)), cpu.rows("f"), "the second message's step: a second execution, its own sample");
        assertEquals(totals(2, 2, 0, 0, 0, 0, 0), cpu.totals());
    }

    /**
     * A step is kept {@code lateness} (2 s) of stream time after its end: a sample of it arriving within that still goes to it; once the
     * stream time passed the step's end by more than the lateness, a sample in its range finds no step and waits pending.
     */
    @Test
    void aLateSampleWithinTheLatenessStillGoesToItsStep() {
        cpu.advance(T + 20 * MS);   // the stream has seen the step's end
        cpu.step(VT, T, T + 20 * MS, "f", 1, "a");
        cpu.advance(T + 20 * MS + Duration.ofSeconds(1).toNanos());   // half the lateness later
        cpu.sample(VT, true, T + 10 * MS, P);
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 1, P)), cpu.rows("f"), "a late sample within the lateness goes to its step");
        assertEquals(totals(1, 1, 0, 0, 0, 0, 0), cpu.totals());

        cpu.advance(T + 20 * MS + Duration.ofSeconds(10).toNanos());   // five times the lateness: the step is no longer kept
        cpu.sample(VT, true, T + 15 * MS, P);
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 1, P)), cpu.rows("f"), "past the lateness the sample does not reach the step");
        assertEquals(totals(2, 1, 1, 0, 0, 0, 0), cpu.totals(), "it waits pending (younger than the max pending age)");
    }

    /**
     * A sample no step of its thread contains stays pending until the stream time is past its age (5 min), then it is unattributed —
     * counted, not lost from the totals — and a step arriving afterwards does not claim it.
     */
    @Test
    void aSampleOutsideEveryStepAgesOutUnattributed() {
        cpu.advance(T + 20 * MS);
        cpu.step(VT, T, T + 20 * MS, "f", 1, "a");
        cpu.sample(VT, true, T + 30 * MS, P);   // after the step on its thread
        cpu.sample(VT2, true, T + 10 * MS, P);   // within the step's time, but another thread
        assertEquals(totals(2, 0, 2, 0, 0, 0, 0), cpu.totals(), "neither is the step's: both wait");

        cpu.advance(T + 30 * MS + Duration.ofMinutes(2).toNanos());
        assertEquals(totals(2, 0, 2, 0, 0, 0, 0), cpu.totals(), "two minutes old: still pending");

        cpu.advance(T + 30 * MS + Duration.ofMinutes(10).toNanos());
        assertEquals(totals(2, 0, 0, 2, 0, 0, 0), cpu.totals(), "past the max pending age: unattributed, counted");
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 0, 0)), cpu.rows("f"), "the step got none of them");

        cpu.step(VT, T + 25 * MS, T + 35 * MS, "f", 2, "b");   // would contain the first one, had it not aged out
        assertEquals(totals(2, 0, 0, 2, 0, 0, 0), cpu.totals(), "an aged-out sample is not claimed any more");
    }

    /** A platform thread's sample counts {@code platform}: never matched to a step, never pending, never unattributed. */
    @Test
    void platformSamplesAreCountedApart() {
        long platformThread = 7;
        cpu.sample(platformThread, false, T + 10 * MS, P);   // before a step on the same thread id
        cpu.step(platformThread, T, T + 20 * MS, "f", 1, "a");
        cpu.sample(platformThread, false, T + 15 * MS, P);   // after it, inside it
        assertEquals(totals(0, 0, 0, 0, 2, 0, 0), cpu.totals(), "two platform samples, no virtual sample");
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 0, 0)), cpu.rows("f"), "the step claims none of them");

        cpu.advance(T + Duration.ofMinutes(10).toNanos());
        assertEquals(totals(0, 0, 0, 0, 2, 0, 0), cpu.totals(), "a platform sample never ages into unattributed");
    }

    /** The samples JFR reports lost add up in {@code lost}; they are counted apart from the samples. */
    @Test
    void lostSamplesAddUp() {
        cpu.lost(3);
        cpu.lost(4);
        cpu.sample(VT, true, T, P);
        assertEquals(totals(1, 0, 1, 0, 0, 7, 0), cpu.totals());
    }

    /** A step's CPU is the sum of its samples' own periods (JFR's samplingPeriod), never samples × one period. */
    @Test
    void cpuIsTheSumOfEachSamplesOwnPeriod() {
        cpu.sample(VT, true, T + 10 * MS, 4_000_000L);
        cpu.sample(VT, true, T + 20 * MS, 8_000_000L);
        cpu.step(VT, T, T + 100 * MS, "f", 1, "a");
        cpu.sample(VT, true, T + 30 * MS, 3_999_999L);   // late
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 3, 15_999_999L)), cpu.rows("f"), "4 ms + 8 ms + 3.999999 ms");
    }

    /**
     * Each map is bounded: past {@code maxPending} a new sample is dropped and counted (the older pending ones stay); past
     * {@code maxRows} a step of a new (flow, step) gets no row and the samples it would claim are dropped and counted; past
     * {@code maxIntervals} a new step is not kept for late samples (an earlier one still is). Every sample stays counted once.
     */
    @Test
    void capsBoundMemoryAndCountTheOverflow() {
        StepCpu pending = new StepCpu(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 2, 16, 16));
        pending.sample(VT, true, T + MS, 1_000_000L);
        pending.sample(VT, true, T + 2 * MS, 2_000_000L);
        pending.sample(VT, true, T + 3 * MS, 4_000_000L);   // the third pending sample: past the cap of 2
        assertEquals(totals(3, 0, 2, 0, 0, 0, 1), pending.totals(), "two pending, the new one dropped and counted");
        pending.step(VT, T, T + 10 * MS, "f", 1, "a");
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 2, 3_000_000L)), pending.rows("f"), "the step claims the two kept: 1 ms + 2 ms, not the dropped 4 ms");
        assertEquals(totals(3, 2, 0, 0, 0, 0, 1), pending.totals());

        StepCpu rows = new StepCpu(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 16, 16, 1));
        rows.step(VT, T, T + 10 * MS, "f", 1, "a");
        rows.sample(VT2, true, T + 5 * MS, P);
        rows.step(VT2, T, T + 10 * MS, "f", 2, "b");   // a second row: past the cap of 1
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 0, 0)), rows.rows("f"), "no row past maxRows");
        assertEquals(totals(1, 0, 0, 0, 0, 0, 1), rows.totals(), "the sample step b claimed is dropped and counted");

        StepCpu intervals = new StepCpu(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 16, 1, 16));
        intervals.step(VT, T, T + 10 * MS, "f", 1, "a");
        intervals.step(VT2, T + 20 * MS, T + 30 * MS, "f", 2, "b");   // a second kept interval: past the cap of 1
        intervals.sample(VT, true, T + 5 * MS, P);   // late for a, which is kept
        intervals.sample(VT2, true, T + 25 * MS, P);   // late for b, which is not
        List<StepCpu.Row> listed = intervals.rows("f");
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 1, P), new StepCpu.Row(2, "b", 1, 0, 0)), listed,
                "the earlier interval still takes its late sample; the one past maxIntervals takes none (its execution counts)");
        StepCpu.Totals t = intervals.totals();
        assertEquals(2, t.samples());
        assertEquals(1, t.attributed());
        assertEquals(t.samples(), t.attributed() + t.pending() + t.unattributed() + t.dropped(), "every sample counted once: " + t);
    }

    /**
     * The cap counts the intervals kept now, not every one ever kept: once the stream time is past the first step's lateness its
     * place under {@code maxIntervals} (1 here) is free again, and the next step is kept — it takes a late sample.
     */
    @Test
    void anEvictedIntervalFreesItsPlaceUnderTheCap() {
        StepCpu one = new StepCpu(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 16, 1, 16));
        one.advance(T + 10 * MS);
        one.step(VT, T, T + 10 * MS, "f", 1, "a");   // the one place
        long later = T + Duration.ofSeconds(10).toNanos();
        one.advance(later);   // five times a's lateness past its end: a is no longer kept
        one.step(VT2, later - 10 * MS, later, "f", 2, "b");
        one.sample(VT2, true, later - 5 * MS, P);   // late for b
        assertEquals(List.of(new StepCpu.Row(2, "b", 1, 1, P), new StepCpu.Row(1, "a", 1, 0, 0)), one.rows("f"),
                "b took the place a left: its late sample is its own");
        assertEquals(totals(1, 1, 0, 0, 0, 0, 0), one.totals());
    }

    /**
     * Stream times are nanoseconds since the epoch (about 1.7e18 now): a step is kept up to and including its lateness after its end
     * and no longer one nanosecond later; a pending sample stays pending up to and including its max age and is unattributed one
     * nanosecond past it.
     */
    @Test
    void boundariesHoldAtEpochScaleTimes() {
        long e = 1_700_000_000_000_000_000L;
        long lateness = Duration.ofSeconds(2).toNanos();
        long age = Duration.ofMinutes(5).toNanos();
        cpu.advance(e + 20 * MS);
        cpu.step(VT, e, e + 20 * MS, "f", 1, "a");
        cpu.sample(VT2, true, e + 30 * MS, P);   // no step of its thread: pending
        assertEquals(totals(1, 0, 1, 0, 0, 0, 0), cpu.totals());

        cpu.advance(e + 20 * MS + lateness);   // exactly the lateness after a's end
        assertEquals(totals(1, 0, 1, 0, 0, 0, 0), cpu.totals(), "the pending sample is far younger than its max age");
        cpu.sample(VT, true, e + 10 * MS, P);
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 1, P)), cpu.rows("f"), "a is still kept at exactly its lateness: the late sample is a's");

        cpu.advance(e + 20 * MS + lateness + 1);   // one nanosecond past it
        cpu.sample(VT, true, e + 15 * MS, P);
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 1, P)), cpu.rows("f"), "a is no longer kept: the sample finds no step");
        assertEquals(totals(3, 1, 2, 0, 0, 0, 0), cpu.totals());

        cpu.advance(e + 30 * MS + age);   // exactly the max age of the sample at 30 ms; 15 ms past the one at 15 ms
        assertEquals(totals(3, 1, 1, 1, 0, 0, 0), cpu.totals(), "at exactly its max age a sample still waits; past it, it is unattributed");
        cpu.advance(e + 30 * MS + age + 1);
        assertEquals(totals(3, 1, 0, 2, 0, 0, 0), cpu.totals(), "one nanosecond past its max age it is unattributed too");
    }

    /** Rows of one flow, by CPU descending, equal CPU by step ascending; another flow's rows are not among them. */
    @Test
    void rowsSortByCpuDescending() {
        cpu.sample(1, true, T + 1 * MS, P);
        cpu.sample(1, true, T + 2 * MS, P);
        cpu.step(1, T, T + 10 * MS, "f", 3, "c");   // arrives first, CPU 2P
        cpu.sample(2, true, T + 1 * MS, P);
        cpu.sample(2, true, T + 2 * MS, P);
        cpu.step(2, T, T + 10 * MS, "f", 1, "a");   // CPU 2P
        cpu.sample(3, true, T + 1 * MS, P);
        cpu.sample(3, true, T + 2 * MS, P);
        cpu.sample(3, true, T + 3 * MS, P);
        cpu.step(3, T, T + 10 * MS, "f", 2, "b");   // CPU 3P
        cpu.sample(4, true, T + 1 * MS, 9 * P);
        cpu.step(4, T, T + 10 * MS, "g", 1, "x");   // another flow, the most CPU of all

        assertEquals(List.of(new StepCpu.Row(2, "b", 1, 3, 3 * P), new StepCpu.Row(1, "a", 1, 2, 2 * P), new StepCpu.Row(3, "c", 1, 2, 2 * P)),
                cpu.rows("f"), "b (3P), then a and c (2P each) by step");
        assertEquals(List.of(new StepCpu.Row(1, "x", 1, 1, 9 * P)), cpu.rows("g"));
        assertEquals(List.of(), cpu.rows("nowhere"), "a flow no step ran in has no rows");
    }

    /** A sample exactly at a step's start or end is the step's, waiting pending or arriving late; one tick outside is not. */
    @Test
    void intervalEndsAreInclusive() {
        long start = T;
        long end = T + 20 * MS;
        cpu.sample(VT, true, start, P);   // pending, at the start
        cpu.sample(VT, true, end, P);   // pending, at the end
        cpu.sample(VT, true, start - 1, P);   // pending, a tick before
        cpu.sample(VT, true, end + 1, P);   // pending, a tick after
        cpu.step(VT, start, end, "f", 1, "a");
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 2, 2 * P)), cpu.rows("f"), "the step claims the pending samples at both ends, not those outside");

        cpu.sample(VT, true, start, P);   // late, at the start
        cpu.sample(VT, true, end, P);   // late, at the end
        cpu.sample(VT, true, start - 1, P);   // late, a tick before
        cpu.sample(VT, true, end + 1, P);   // late, a tick after
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 4, 4 * P)), cpu.rows("f"), "late samples at both ends go to it, those outside do not");
        assertEquals(totals(8, 4, 4, 0, 0, 0, 0), cpu.totals());
    }

    /**
     * Two steps of one thread with the same start (a stage and the step it wraps can start in one tick): the step that arrived first
     * holds a sample inside both, whether the sample waited pending for it or arrives after both — the pending path and the late
     * path agree, in either order of arrival.
     */
    @Test
    void ofTwoStepsStartingTogetherTheFirstToArriveHoldsTheSample() {
        cpu.sample(VT, true, T + 5 * MS, P);   // pending, inside both
        cpu.step(VT, T, T + 10 * MS, "f", 2, "short");   // arrives first
        cpu.step(VT, T, T + 20 * MS, "f", 1, "long");   // the same start, arrives second
        cpu.sample(VT, true, T + 6 * MS, P);   // late, inside both
        cpu.sample(VT, true, T + 15 * MS, P);   // late, inside the long one only
        assertEquals(List.of(new StepCpu.Row(2, "short", 1, 2, 2 * P), new StepCpu.Row(1, "long", 1, 1, P)), cpu.rows("f"),
                "short arrived first: it holds the pending sample at 5 ms and the late one at 6 ms; long holds 15 ms");

        cpu.sample(VT2, true, T + 5 * MS, P);   // pending, inside both
        cpu.step(VT2, T, T + 20 * MS, "g", 1, "long");   // arrives first this time
        cpu.step(VT2, T, T + 10 * MS, "g", 2, "short");   // the same start, arrives second
        cpu.sample(VT2, true, T + 6 * MS, P);   // late, inside both
        assertEquals(List.of(new StepCpu.Row(1, "long", 1, 2, 2 * P), new StepCpu.Row(2, "short", 1, 0, 0)), cpu.rows("g"),
                "long arrived first: it holds both, short none");
        assertEquals(totals(5, 5, 0, 0, 0, 0, 0), cpu.totals());
    }

    /**
     * A lane — one thread's pending samples and kept steps — lives only while it holds something: after steps and samples of many
     * threads, an advance past the lateness leaves the lanes of the pending samples only, one past the max pending age leaves none.
     * A node's message threads are never reused, so without that removal the lanes would grow without bound (review r1, blocking 3).
     * {@code lanes()} is package-private: the lanes the matcher holds now.
     */
    @Test
    void aLaneEndsWithItsLastKeptStepAndPendingSample() {
        int threads = 1_000;
        for (int t = 0; t < threads; t++) {
            cpu.step(10_000 + t, T, T + 10 * MS, "f", 1, "a");   // kept for late samples
            cpu.sample(20_000 + t, true, T + 5 * MS, P);   // pending: no step of its thread
        }
        assertEquals(2 * threads, cpu.lanes(), "witness: a lane per thread holding a kept step or a pending sample");
        cpu.advance(T + 10 * MS + Duration.ofSeconds(2).toNanos() + 1);
        assertEquals(threads, cpu.lanes(), "past the lateness the steps' lanes are gone; the pending samples' lanes remain");
        cpu.advance(T + 5 * MS + Duration.ofMinutes(5).toNanos() + 1);
        assertEquals(0, cpu.lanes(), "past the max pending age no lane is left");
        assertEquals(totals(threads, 0, 0, threads, 0, 0, 0), cpu.totals());
        assertEquals(List.of(new StepCpu.Row(1, "a", threads, 0, 0)), cpu.rows("f"), "the rows outlive the lanes");
    }
}
