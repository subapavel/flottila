package dev.rail8.runtime.node;

import dev.rail8.core.record.StepCpu;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-264: {@link CpuSampler#start} on an OS other than Linux — where {@code jdk.CPUTimeSample} does not exist (F-21, FIX-108) — is
 * refused with an {@link IllegalStateException} naming the OS, and leaves nothing recording and nothing scheduled. The other
 * refusal, no flight recorder, is {@code CpuSamplerIT#startRefusesWithoutFlightRecorder} (a child JVM). The OS is the
 * {@code os.name} system property, set for the call and restored after it.
 */
class CpuSamplerStartTest {

    static final String SAMPLER = "rail8-cpu-sampler";

    static long samplerRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream().map(Recording::getName).filter(SAMPLER::equals).count();
    }

    @Test
    void startOnAnOsOtherThanLinuxIsRefusedNamingTheOsAndLeavesNothingRecording() {
        assertTrue(FlightRecorder.isAvailable(), "fixture: this JVM has a flight recorder, so the refusal can only be the OS's");
        FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        FakeTimer timer = new FakeTimer(clock);
        long before = samplerRecordings();
        String os = System.getProperty("os.name");
        IllegalStateException refused;
        System.setProperty("os.name", "Plan 9");
        try {
            refused = assertThrows(IllegalStateException.class,
                    () -> CpuSampler.start(Duration.ofMillis(4), new StepCpu(StepCpu.Limits.DEFAULT), clock, timer, Duration.ofSeconds(1)));
        } finally {
            System.setProperty("os.name", os);
        }

        String text = refused.getMessage();
        assertTrue(text.contains("os.name 'Plan 9'"), "the refusal names the OS: " + text);
        assertTrue(text.contains("F-21") && text.contains("flight recorder available true"), "the OS's refusal, not the recorder's: " + text);
        assertEquals(before, samplerRecordings(), "no sampler recording was left by the refused start");
        assertEquals(0, timer.pending(), "no read cycle scheduled");
    }
}
