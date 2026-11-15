package io.flottila.core.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * CORE-14a P1 (D-135 draft): an at-least-once flow's schedule of retries, {@code delivery.retries {count 0..100, interval 1s..24h,
 * multiplier ≥ 1 (default 1), maxInterval interval..24h (default the interval)}} in the flow file's durations, read into the
 * existing {@link RetryPolicy} (count = its attempts, interval = its initial, maxInterval = its max): the wait before retry k is
 * {@code delayBefore(k)}. Omitted, the flow has none of its own and runs under the node's default (the builder's, see
 * {@code FlowWorkerRetryTest}); set on a sync or at-most-once flow it is refused at load naming the key; a value out of its bounds is
 * refused naming its key.
 *
 * <p>The API assumed (CORE-14a, test-author): {@code Optional<RetryPolicy> FlowDefinition.retries()} — empty when the flow names
 * none; {@code count} and {@code interval} required inside {@code delivery.retries}.
 */
class FlowDefinitionRetriesTest {

    /** An at-least-once flow document whose {@code delivery} is {@code mode} with {@code retries} (none when null). */
    static Map<String, Object> doc(String mode, Object retries) {
        Map<String, Object> doc = new HashMap<>(FlowDefinitionTest.demo());
        doc.remove("partitionBy");
        doc.remove("onFailure");
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("mode", mode);
        if (retries != null) delivery.put("retries", retries);
        doc.put("delivery", delivery);
        return doc;
    }

    static Map<String, Object> retries(Object... keysAndValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    @Test
    void aFlowsRetriesLoadAsItsSchedule() {
        FlowDefinition f = FlowDefinition.from(doc("at-least-once", retries("count", 2, "interval", "1m", "multiplier", 2, "maxInterval", "3m")));
        assertEquals(Optional.of(new RetryPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(3), 2.0)), f.retries());
        assertEquals(Duration.ofMinutes(1), f.retries().orElseThrow().delayBefore(1), "worked example: the wait before retry 1");
        assertEquals(Duration.ofMinutes(2), f.retries().orElseThrow().delayBefore(2), "× 2");
        assertEquals(Duration.ofMinutes(3), f.retries().orElseThrow().delayBefore(3), "capped at maxInterval 3m, not 4m");
        assertEquals(new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0), f.retry(),
                "the in-thread retry of retryable steps is a key of its own, unchanged");
    }

    @Test
    void theMultiplierDefaultsToOneAndTheMaxIntervalToTheInterval() {
        FlowDefinition f = FlowDefinition.from(doc("at-least-once", retries("count", 4, "interval", "90s")));
        assertEquals(Optional.of(new RetryPolicy(4, Duration.ofSeconds(90), Duration.ofSeconds(90), 1.0)), f.retries());
        FlowDefinition growing = FlowDefinition.from(doc("at-least-once", retries("count", 4, "interval", "90s", "multiplier", 1.5)));
        assertEquals(Optional.of(new RetryPolicy(4, Duration.ofSeconds(90), Duration.ofSeconds(90), 1.5)), growing.retries(),
                "without a maxInterval the interval caps the growth");
    }

    @Test
    void theEdgesOfEachBoundLoad() {
        assertEquals(Optional.of(new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0)),
                FlowDefinition.from(doc("at-least-once", retries("count", 0, "interval", "1s"))).retries(), "count 0, interval 1s");
        assertEquals(Optional.of(new RetryPolicy(100, Duration.ofHours(24), Duration.ofHours(24), 1.0)),
                FlowDefinition.from(doc("at-least-once", retries("count", 100, "interval", "24h", "maxInterval", "24h"))).retries(), "count 100, 24h");
        assertEquals(Optional.of(new RetryPolicy(1, Duration.ofMillis(1_000), Duration.ofMinutes(1_440), 1.0)),
                FlowDefinition.from(doc("at-least-once", retries("count", 1, "interval", "1000ms", "maxInterval", "1440m"))).retries(),
                "1000ms is 1s and 1440m is 24h: the bounds are durations, not spellings");
    }

    @Test
    void omittedRetriesLeaveTheNodeDefault() {
        assertEquals(Optional.empty(), FlowDefinition.from(doc("at-least-once", null)).retries(), "no schedule of its own: the node's applies");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "at-most-once"})
    void retriesAreRefusedOutsideAtLeastOnce(String mode) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FlowDefinition.from(doc(mode, retries("count", 1, "interval", "1m"))));
        assertTrue(e.getMessage().contains("delivery.retries"), mode + ": names the key: " + e.getMessage());
        assertEquals(Optional.empty(), FlowDefinition.from(doc(mode, null)).retries(), mode + " without the key loads");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> outOfBounds() {
        return Stream.of(
                arguments("delivery.retries.count", retries("count", -1, "interval", "1m")),
                arguments("delivery.retries.count", retries("count", 101, "interval", "1m")),
                arguments("delivery.retries.count", retries("count", 2.5, "interval", "1m")),
                arguments("delivery.retries.count", retries("count", "3", "interval", "1m")),
                arguments("delivery.retries.count", retries("interval", "1m")),
                arguments("delivery.retries.interval", retries("count", 1)),
                arguments("delivery.retries.interval", retries("count", 1, "interval", "999ms")),
                arguments("delivery.retries.interval", retries("count", 1, "interval", "0s")),
                arguments("delivery.retries.interval", retries("count", 1, "interval", "25h")),
                arguments("delivery.retries.interval", retries("count", 1, "interval", "1441m")),
                arguments("delivery.retries.interval", retries("count", 1, "interval", "PT5M")),
                arguments("delivery.retries.multiplier", retries("count", 1, "interval", "1m", "multiplier", 0.5)),
                arguments("delivery.retries.multiplier", retries("count", 1, "interval", "1m", "multiplier", "2")),
                arguments("delivery.retries.maxInterval", retries("count", 1, "interval", "1m", "maxInterval", "59s")),
                arguments("delivery.retries.maxInterval", retries("count", 1, "interval", "1m", "maxInterval", "86401s")),
                arguments("delivery.retries.maxInterval", retries("count", 1, "interval", "1m", "maxInterval", "soon")),
                arguments("delivery.retries", List.of(1, "1m")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("outOfBounds")
    void aRetriesValueOutOfItsBoundsIsRefusedNamingItsKey(String key, Object retries) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(doc("at-least-once", retries)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }
}
