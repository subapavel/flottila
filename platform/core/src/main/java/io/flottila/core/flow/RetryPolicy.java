package io.flottila.core.flow;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The flow's retry policy for steps marked {@code retryable} (schema v1 {@code retry}, D-040): up to
 * {@code attempts} retries after the first try, exponential backoff from {@code initial} by
 * {@code multiplier}, capped at {@code max}. {@link #NONE} means a failure surfaces at once.
 *
 * @param attempts retries after the first try, 0..n
 * @param initial delay before the first retry
 * @param max cap of the delay; never below {@code initial}
 * @param multiplier growth factor per retry, at least 1
 * @since 0.1
 */
public record RetryPolicy(int attempts, Duration initial, Duration max, double multiplier) {

    /** No retries: the step runs once. */
    public static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 1.0);

    /** The schema's duration strings: a whole number with the unit {@code ms}, {@code s}, {@code m} or {@code h}. */
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");

    public RetryPolicy {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (attempts < 0) throw new IllegalArgumentException("retry.attempts must not be negative, was " + attempts);
        if (initial.isNegative()) throw new IllegalArgumentException("retry.initial must not be negative, was " + initial);
        if (max.compareTo(initial) < 0) throw new IllegalArgumentException("retry.max " + max + " is below retry.initial " + initial);
        if (!(multiplier >= 1.0)) throw new IllegalArgumentException("retry.multiplier must be at least 1, was " + multiplier);
    }

    /** The delay before retry number {@code retry} (1 = the first retry): {@code initial × multiplier^(retry−1)}, capped at {@code max}. */
    public Duration delayBefore(int retry) {
        if (retry < 1) throw new IllegalArgumentException("retry number starts at 1, was " + retry);
        double nanos = initial.toNanos() * Math.pow(multiplier, retry - 1);
        if (nanos >= max.toNanos()) return max;
        return Duration.ofNanos((long) nanos);
    }

    /** Parses the schema's {@code ^\d+(ms|s|m|h)$}; anything else is an {@link IllegalArgumentException}. */
    public static Duration parseDuration(String text) {
        Objects.requireNonNull(text, "duration");
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("not a duration (<number>ms|s|m|h): '" + text + "'");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
