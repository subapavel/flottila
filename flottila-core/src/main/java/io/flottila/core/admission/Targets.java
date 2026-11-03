package io.flottila.core.admission;

import io.flottila.api.Timer;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The node's per-target limits (D-099): one {@link TargetLimit} per target key for the lifetime of this object, made on first
 * use with the override for its key or else the defaults, so every flow naming a target shares its limit. A component that
 * names no target gets an {@link #unshared()} limit with the defaults — finite, never unlimited, and not listed. A bad limit
 * (not positive) is refused when its {@link TargetLimit} is made.
 *
 * @since 0.1
 */
public final class Targets {

    /** The node's defaults until configured otherwise: 16 calls in flight per target, a waiter refused after 5 s. */
    public static final Limit DEFAULTS = new Limit(16, Duration.ofSeconds(5));

    private final Limit defaults;
    private final Map<String, Limit> overrides;
    private final Timer timer;
    private final Map<String, TargetLimit> limits = new ConcurrentHashMap<>();

    public Targets(Limit defaults, Map<String, Limit> overrides, Timer timer) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.overrides = Map.copyOf(overrides);
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** The limit of {@code target}: the same one for every caller naming it, as long as this object lives. */
    public TargetLimit of(String target) {
        Objects.requireNonNull(target, "target");
        return limits.computeIfAbsent(target, t -> limit(t, overrides.getOrDefault(t, defaults)));
    }

    /** A limit of its own with the defaults, for a component that names no target; not listed by {@link #gauges()}. */
    public TargetLimit unshared() {
        return limit("(unshared)", defaults);
    }

    /** Every keyed limit's gauge, sorted by target. */
    public List<TargetLimit.Gauge> gauges() {
        return limits.values().stream().map(TargetLimit::gauge).sorted(Comparator.comparing(TargetLimit.Gauge::target)).toList();
    }

    private TargetLimit limit(String target, Limit limit) {
        return new TargetLimit(target, limit.maxCalls(), limit.maxWait(), timer);
    }

    /**
     * How many sends one target may have in flight, and how long a send waits for a permit before it is refused.
     *
     * @since 0.1
     */
    public record Limit(int maxCalls, Duration maxWait) {}
}
