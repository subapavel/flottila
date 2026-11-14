package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.NackException;
import io.flottila.core.dlq.Parked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE-14a P1, P4 (D-135 draft): for any {@code delivery.retries} schedule, attempt k + 1 of a message that keeps failing never
 * runs before the sum of the waits before retries 1..k — each {@code interval × multiplier^(j−1)} capped at {@code maxInterval},
 * computed here in exact decimal arithmetic (never by the production helper), truncated to whole nanoseconds — and it does run
 * there, by the worker's own wake (its reconciliation is a week apart); after {@code count + 1} sends the ingress is parked. Each try
 * builds its own {@link FlowWorkerRetryTest.World}; the fake clock drives every due.
 */
class RetrySchedulePropertiesTest {

    @Provide
    Arbitrary<Double> multipliers() {
        return Arbitraries.of(1.0, 1.5, 2.0, 3.0);
    }

    @Property(tries = 40)
    void attemptKNeverBeforeItsSummedDelays(@ForAll @IntRange(min = 0, max = 4) int count, @ForAll @IntRange(min = 1, max = 3_600) int intervalSeconds,
                                            @ForAll("multipliers") Double multiplier, @ForAll @IntRange(min = 1, max = 30) int capFactor) throws Exception {
        long maxSeconds = Math.min((long) intervalSeconds * capFactor, Duration.ofHours(24).toSeconds());
        FlowWorkerRetryTest.World w = new FlowWorkerRetryTest.World();
        try {
            w.partner.failNext(count + 1, () -> new NackException("partner down", true));
            FlowBuilder b = w.builder();
            FlowDefinition def = FlowDefinition.from(FlowWorkerRetryTest.flow(Map.of("count", count, "interval", intervalSeconds + "s",
                    "multiplier", multiplier, "maxInterval", maxSeconds + "s"), List.of(FlowWorkerRetryTest.REWRITE, TO_PARTNER)));
            FlowWorker worker = w.started(b, def, "n1");
            Envelope in = w.accept(b.delivery(def, NO_FILES));
            w.sent(worker, 1);

            Instant due = w.t0;
            for (int k = 1; k <= count; k++) {
                due = due.plus(expectedWaitBefore(k, intervalSeconds, multiplier, maxSeconds));
                w.retryAt(worker, "n1", due, k + 1);
            }
            List<Parked> parked = w.dlq();
            assertEquals(1, parked.size(), "parked after " + (count + 1) + " sends: " + parked);
            assertEquals(in.id(), parked.get(0).messageId());
            assertEquals(Optional.of(count + 1), parked.get(0).props().get(Envelope.ATTEMPT));
            assertEquals(count + 1, w.partner.sent().size(), "count + 1 sends, no more");
        } finally {
            w.close();
        }
    }

    /** {@code interval × multiplier^(k−1)} capped at {@code max}, in exact decimals, truncated to whole nanoseconds. */
    static Duration expectedWaitBefore(int k, long intervalSeconds, double multiplier, long maxSeconds) {
        BigDecimal nanos = BigDecimal.valueOf(intervalSeconds).multiply(BigDecimal.valueOf(1_000_000_000L))
                .multiply(BigDecimal.valueOf(multiplier).pow(k - 1));
        BigDecimal cap = BigDecimal.valueOf(maxSeconds).multiply(BigDecimal.valueOf(1_000_000_000L));
        BigDecimal wait = nanos.min(cap).setScale(0, RoundingMode.DOWN);
        return Duration.ofNanos(wait.longValueExact());
    }
}
