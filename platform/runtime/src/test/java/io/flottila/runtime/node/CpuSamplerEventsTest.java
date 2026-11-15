package io.flottila.runtime.node;

import io.flottila.core.record.StepCpu;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-108 review r1, blocking 1: under load JFR hands some events without their thread ({@code RecordedEvent.getThread()} null — the
 * bench's NPEs at CpuSampler:177/:183, executions 19 827 of 21 000). Decided (packet "[A] after review r1"): a step without a thread
 * counts its execution and keeps no interval; a sample without a thread counts {@code unattributed}, so {@code samples = attributed +
 * pending + unattributed + dropped} still holds. {@link StepCpu} itself takes no null: the seam is the sampler's mapping of one event's
 * fields onto it.
 *
 * <p>The shape assumed (FIX-108 test-author, round 3): in {@link CpuSampler}, package-private
 * {@code static void onStep(StepCpu cpu, Long thread, long startNs, long endNs, String flow, int step, String name)} and
 * {@code static void onSample(StepCpu cpu, Long thread, boolean virtual, long atNs, long periodNs)}, {@code thread} the event's Java
 * thread id or null; the stream's handlers call them.
 */
class CpuSamplerEventsTest {

    static final long T = 10_000_000_000L;
    static final long MS = 1_000_000L;
    static final long P = 4 * MS;
    static final long VT = 41;
    static final long VT2 = 42;

    static StepCpu.Totals totals(long samples, long attributed, long pending, long unattributed, long platform, long lost, long dropped) {
        return new StepCpu.Totals(samples, attributed, pending, unattributed, platform, lost, dropped);
    }

    /** Events with their thread reach the matcher as they are: a pending sample, its step, a late sample, a platform sample. */
    @Test
    void eventsWithTheirThreadReachTheMatcherAsTheyAre() {
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        CpuSampler.onSample(cpu, VT, true, T + 5 * MS, P);   // pending
        CpuSampler.onStep(cpu, VT, T, T + 10 * MS, "f", 1, "a");   // claims it
        CpuSampler.onSample(cpu, VT, true, T + 6 * MS, 3 * P);   // late, a's
        CpuSampler.onSample(cpu, 7L, false, T + 6 * MS, P);   // a platform thread's
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 2, 4 * P)), cpu.rows("f"));
        assertEquals(totals(2, 2, 0, 0, 1, 0, 0), cpu.totals());
    }

    /**
     * A step without a thread counts one execution, holds no sample (it claims no thread's pending one) and keeps no interval: under
     * a cap of one kept interval, a later step with its thread still takes the place and its late sample.
     */
    @Test
    void aStepWithoutItsThreadCountsItsExecutionAndKeepsNoInterval() {
        StepCpu cpu = new StepCpu(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMinutes(5), 16, 1, 16));   // one kept interval at most
        CpuSampler.onSample(cpu, VT, true, T + 5 * MS, P);   // pending on VT
        CpuSampler.onStep(cpu, null, T, T + 10 * MS, "f", 1, "threadless");
        assertEquals(List.of(new StepCpu.Row(1, "threadless", 1, 0, 0)), cpu.rows("f"), "one execution, no sample");

        CpuSampler.onStep(cpu, VT2, T, T + 10 * MS, "f", 2, "b");
        CpuSampler.onSample(cpu, VT2, true, T + 5 * MS, P);   // late: b's only if b was kept
        assertEquals(List.of(new StepCpu.Row(2, "b", 1, 1, P), new StepCpu.Row(1, "threadless", 1, 0, 0)), cpu.rows("f"),
                "b took the one place of a kept interval: the threadless step kept none");
        assertEquals(totals(2, 1, 1, 0, 0, 0, 0), cpu.totals(), "VT's sample still waits for its own step");
    }

    /**
     * A sample without a thread is unattributed at once — whatever the virtual flag says, since nothing is known of its thread — and
     * never held by a step, even one whose time holds it.
     */
    @Test
    void aSampleWithoutItsThreadIsUnattributed() {
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        CpuSampler.onStep(cpu, VT, T, T + 10 * MS, "f", 1, "a");
        CpuSampler.onSample(cpu, null, true, T + 5 * MS, P);
        CpuSampler.onSample(cpu, null, false, T + 6 * MS, P);
        assertEquals(List.of(new StepCpu.Row(1, "a", 1, 0, 0)), cpu.rows("f"), "inside a's time, but of no known thread: not a's");
        assertEquals(totals(2, 0, 0, 2, 0, 0, 0), cpu.totals(), "samples = attributed + pending + unattributed + dropped still holds");
    }
}
