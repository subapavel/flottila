package io.flottila.runtime.journal;

import java.time.Duration;
import java.util.Objects;

/**
 * The segment sweeper's settings (CORE-3 items 10 and 14), each finite and range-checked; a refusal names its key.
 *
 * @param interval   {@code FLOTTILA_JOURNAL_SWEEP_INTERVAL} (PT10S, PT1S..PT1H): from the end of a pass to the next one
 * @param grace      {@code FLOTTILA_JOURNAL_SWEEP_GRACE} (PT1M, PT1S..PT1H): a segment is a candidate this long after its seal
 *                   (by its trailer's sealed ms; after a restart, since the start)
 * @param keepMaxAge {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE} (PT1H, PT1M..P1D): a kept ref pins its segment while it is younger
 *                   (the interim until CORE-10, CORE-3 item 14)
 * @since 0.1
 */
public record SweepConfig(Duration interval, Duration grace, Duration keepMaxAge) {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration HOUR = Duration.ofHours(1);

    /** The design's defaults: PT10S, PT1M, PT1H (after the bounds: the constructor reads them). */
    public static final SweepConfig DEFAULTS = new SweepConfig(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofHours(1));

    public SweepConfig {
        within("FLOTTILA_JOURNAL_SWEEP_INTERVAL", interval, SECOND, HOUR);
        within("FLOTTILA_JOURNAL_SWEEP_GRACE", grace, SECOND, HOUR);
        within("FLOTTILA_JOURNAL_KEEP_MAX_AGE", keepMaxAge, Duration.ofMinutes(1), Duration.ofDays(1));
    }

    private static void within(String key, Duration value, Duration min, Duration max) {
        Objects.requireNonNull(value, key);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(key + " must lie in " + min + ".." + max + ", was " + value);
        }
    }
}
