package io.flottila.core.flow;

import io.flottila.api.StepConfig;
import io.flottila.core.record.RecordingPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A flow file (schema v1, D-040) as core sees it: a plain map in (the YAML as {@code flottila-schemas}
 * parsed and validated it), a typed definition out. Core does not depend on the schema module, so the
 * parts it needs are checked again here with the same paths in the messages ({@code steps[2]}); a file
 * that passed the schema never fails here (except a zero {@code saveTimeout} or {@code timeout}, which the schema admits —
 * FIX-119, and {@code onFailure: block}, which the schema admits until FIX-141). {@code recording} is the flow's policy (default: all, keep); a step's own
 * {@code recording} stays in its config and overrides it in the builder. {@code saveTimeout} bounds an at-least-once
 * flow's durable save ({@code delivery.saveTimeout}, default {@link #DEFAULT_SAVE_TIMEOUT}); {@code timeout} is the time each run of
 * a message has, in any delivery mode — its deadline is the run's start plus it ({@code delivery.timeout}, default
 * {@link #DEFAULT_TIMEOUT}, D-100); a body limit above the
 * in-flight byte limit is refused, since such a body could never be admitted (D-049 f). {@code onFailure} is what a keyed
 * flow does with an item that failed for good (F-32, D-125): a {@code partitionBy} flow must state it and nothing else may;
 * {@code park} parks the item and lets its key's next item run, {@code block} is refused until FIX-141's durable barrier.
 * {@code retries} is an at-least-once flow's own schedule of retries ({@code delivery.retries}, CORE-14a, D-135 draft: refused on
 * any other mode); empty, its worker runs under the node's default ({@link FlowBuilder#retries}). {@code retry} is the in-thread
 * retry of the steps marked {@code retryable}, a key of its own.
 *
 * @since 0.1
 */
public record FlowDefinition(String id, String version, String fromEndpoint, String deliveryMode, Duration saveTimeout, Duration timeout,
                             long maxBodyBytes, Optional<Long> maxInFlight, Optional<Long> maxInFlightBytes, Optional<String> partitionBy,
                             Optional<String> onFailure, List<String> forwardHeaders, RetryPolicy retry, Optional<RetryPolicy> retries, RecordingPolicy recording,
                             List<StepConfig> steps) {

    /** Keys of a step entry that are the step's shape, not its configuration. */
    private static final Set<String> STRUCTURAL = Set.of("type", "name", "retryable");

    /** How long an at-least-once save may take before the sender is told to retry, when the flow names nothing (ARCHITECTURE §4). */
    public static final Duration DEFAULT_SAVE_TIMEOUT = Duration.ofSeconds(5);

    /** The time each run of a message has when the flow names none (D-100). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** {@code onFailure: park}: an item parked past its attempts lets its key's next item run (F-32). */
    public static final String PARK = "park";

    /** {@code onFailure: block}: the key waits for its parked item; refused until FIX-141. */
    public static final String BLOCK = "block";

    /** The most retries {@code delivery.retries.count} may schedule (D-135 draft). */
    public static final int MAX_RETRIES = 100;

    /** The shortest {@code delivery.retries.interval}. */
    public static final Duration MIN_RETRY_INTERVAL = Duration.ofSeconds(1);

    /** The longest {@code delivery.retries.interval} and {@code maxInterval}. */
    public static final Duration MAX_RETRY_INTERVAL = Duration.ofHours(24);

    public FlowDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(fromEndpoint, "from.endpoint");
        Objects.requireNonNull(deliveryMode, "delivery.mode");
        Objects.requireNonNull(saveTimeout, "delivery.saveTimeout");
        if (saveTimeout.compareTo(Duration.ZERO) <= 0) throw new IllegalArgumentException("delivery.saveTimeout must be positive, was " + saveTimeout);
        Objects.requireNonNull(timeout, "delivery.timeout");
        if (!timeout.isPositive()) throw new IllegalArgumentException("delivery.timeout must be positive, was " + timeout);
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(retries, "delivery.retries");
        retries.ifPresent(r -> withinRetryBounds(r, "delivery.retries"));
        if (retries.isPresent() && !"at-least-once".equals(deliveryMode)) {
            throw new IllegalArgumentException("delivery.retries schedules the retries of an at-least-once flow; a " + deliveryMode
                    + " flow has no durable item to retry: remove it");
        }
        Objects.requireNonNull(recording, "recording");
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("limits.maxBodyBytes must be positive, was " + maxBodyBytes);
        if (maxInFlightBytes.isPresent() && maxBodyBytes > maxInFlightBytes.get()) {
            throw new IllegalArgumentException("limits.maxBodyBytes " + maxBodyBytes + " exceeds limits.maxInFlightBytes " + maxInFlightBytes.get()
                    + ": such a body could never be admitted");
        }
        onFailure(partitionBy, onFailure);
        forwardHeaders = List.copyOf(forwardHeaders);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("steps must name at least one step");
    }

    /** A definition whose messages have the {@link #DEFAULT_TIMEOUT} per run and that states no {@code onFailure} (so it has no {@code partitionBy}). */
    public FlowDefinition(String id, String version, String fromEndpoint, String deliveryMode, Duration saveTimeout, long maxBodyBytes,
                          Optional<Long> maxInFlight, Optional<Long> maxInFlightBytes, Optional<String> partitionBy,
                          List<String> forwardHeaders, RetryPolicy retry, RecordingPolicy recording, List<StepConfig> steps) {
        this(id, version, fromEndpoint, deliveryMode, saveTimeout, DEFAULT_TIMEOUT, maxBodyBytes, maxInFlight, maxInFlightBytes, partitionBy,
                Optional.empty(), forwardHeaders, retry, Optional.empty(), recording, steps);
    }

    /** Builds the definition from the parsed flow document. */
    public static FlowDefinition from(Map<String, Object> doc) {
        Objects.requireNonNull(doc, "flow document");
        Map<String, Object> from = section(doc, "from");
        Map<String, Object> delivery = section(doc, "delivery");
        Map<String, Object> limits = section(doc, "limits");
        return new FlowDefinition(
                string(doc, "id"), string(doc, "version"), string(from, "from.endpoint", "endpoint"), string(delivery, "delivery.mode", "mode"),
                delivery.get("saveTimeout") == null ? DEFAULT_SAVE_TIMEOUT : duration(delivery, "delivery.saveTimeout", "saveTimeout"),
                delivery.get("timeout") == null ? DEFAULT_TIMEOUT : duration(delivery, "delivery.timeout", "timeout"),
                number(limits, "limits.maxBodyBytes", "maxBodyBytes").orElseThrow(() -> new IllegalArgumentException("limits.maxBodyBytes is required")),
                number(limits, "limits.maxInFlight", "maxInFlight"), number(limits, "limits.maxInFlightBytes", "maxInFlightBytes"),
                Optional.ofNullable(doc.get("partitionBy")).map(Object::toString), Optional.ofNullable(doc.get("onFailure")).map(Object::toString),
                strings(doc.get("forwardHeaders")), retry(doc.get("retry")), retries(delivery.get("retries")), RecordingPolicy.from(doc.get("recording"), RecordingPolicy.DEFAULT), steps(doc.get("steps")));
    }

    /**
     * F-32 (D-125): a keyed flow states what a failed item does to its key, and only a keyed flow does. {@code park} is the one
     * value that loads; {@code block} waits for a barrier that survives a restart (FIX-141) and is never taken for park.
     */
    private static void onFailure(Optional<String> partitionBy, Optional<String> onFailure) {
        if (onFailure.isEmpty()) {
            if (partitionBy.isPresent()) {
                throw new IllegalArgumentException("partitionBy '" + partitionBy.get() + "' needs onFailure: park (an item parked past its attempts"
                        + " lets the key's next item run); block (the key waits for the parked item) is not available yet (FIX-141)");
            }
            return;
        }
        String value = onFailure.get();
        if (!value.equals(PARK) && !value.equals(BLOCK)) throw new IllegalArgumentException("onFailure must be park or block, was '" + value + "'");
        if (partitionBy.isEmpty()) throw new IllegalArgumentException("onFailure needs partitionBy: a flow without a key has no key to park or block");
        if (value.equals(BLOCK)) {
            throw new IllegalArgumentException("onFailure: block on partitionBy '" + partitionBy.get() + "' is not available yet: it needs the durable"
                    + " barrier with release and discard of FIX-141; use onFailure: park");
        }
    }

    /** Endpoints the steps send to, in order, each once: {@code to.endpoint} and the values of {@code to.endpoints}. The {@code from} endpoint is a consumer and not listed. */
    public List<String> referencedEndpoints() {
        List<String> names = new ArrayList<>();
        for (StepConfig step : steps) {
            if (step.config().get("endpoint") instanceof String s && !names.contains(s)) names.add(s);
            if (step.config().get("endpoints") instanceof Map<?, ?> m) {
                for (Object v : m.values()) if (v instanceof String s && !names.contains(s)) names.add(s);
            }
        }
        return List.copyOf(names);
    }

    private static RetryPolicy retry(Object node) {
        if (node == null) return RetryPolicy.NONE;
        if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("retry must be an object");
        Object multiplier = m.get("multiplier");
        return new RetryPolicy(
                Math.toIntExact(number(m, "retry.attempts", "attempts").orElseThrow(() -> new IllegalArgumentException("retry.attempts is required"))),
                RetryPolicy.parseDuration(string(m, "retry.initial", "initial")),
                RetryPolicy.parseDuration(string(m, "retry.max", "max")),
                multiplier instanceof Number n ? n.doubleValue() : 2.0);
    }

    /**
     * {@code delivery.retries} (CORE-14a P1, D-135 draft): empty when absent (the node's default applies); else {@code count}
     * 0..{@value #MAX_RETRIES} and {@code interval} 1s..24h, both required, {@code multiplier} a number of at least 1 (default 1),
     * {@code maxInterval} from the interval to 24h (default the interval) — as a {@link RetryPolicy} whose attempts are the count,
     * initial the interval and max the maxInterval. A value out of its bounds is refused naming its key.
     */
    private static Optional<RetryPolicy> retries(Object node) {
        if (node == null) return Optional.empty();
        if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("delivery.retries must be an object with count and interval");
        long count = number(m, "delivery.retries.count", "count").orElseThrow(() -> new IllegalArgumentException("delivery.retries.count is required"));
        if (count > MAX_RETRIES) throw new IllegalArgumentException("delivery.retries.count must be at most " + MAX_RETRIES + ", was " + count);
        Duration interval = retryInterval(m, "interval", MIN_RETRY_INTERVAL);
        Duration maxInterval = m.get("maxInterval") == null ? interval : retryInterval(m, "maxInterval", interval);
        Object multiplier = m.get("multiplier");
        if (multiplier != null && !(multiplier instanceof Number n && n.doubleValue() >= 1.0)) {
            throw new IllegalArgumentException("delivery.retries.multiplier must be a number of at least 1, was " + multiplier);
        }
        return Optional.of(new RetryPolicy((int) count, interval, maxInterval, multiplier == null ? 1.0 : ((Number) multiplier).doubleValue()));
    }

    /**
     * {@code policy} when it is within the D-135 bounds — at most {@value #MAX_RETRIES} retries (so a worker's bound, count + 1,
     * never overflows), an interval from {@link #MIN_RETRY_INTERVAL} to {@link #MAX_RETRY_INTERVAL} and a cap no longer than
     * that; else refused naming {@code what}. A schedule read from a flow file is held to them key by key before it gets here.
     */
    static RetryPolicy withinRetryBounds(RetryPolicy policy, String what) {
        if (policy.attempts() > MAX_RETRIES) {
            throw new IllegalArgumentException(what + ": count must be at most " + MAX_RETRIES + ", was " + policy.attempts());
        }
        if (policy.initial().compareTo(MIN_RETRY_INTERVAL) < 0 || policy.initial().compareTo(MAX_RETRY_INTERVAL) > 0) {
            throw new IllegalArgumentException(what + ": interval must be from " + MIN_RETRY_INTERVAL + " to " + MAX_RETRY_INTERVAL + ", was " + policy.initial());
        }
        if (policy.max().compareTo(MAX_RETRY_INTERVAL) > 0) {
            throw new IllegalArgumentException(what + ": maxInterval must be at most " + MAX_RETRY_INTERVAL + ", was " + policy.max());
        }
        return policy;
    }

    /** {@code delivery.retries.<key>}: a duration from {@code min} to {@link #MAX_RETRY_INTERVAL}, else refused naming the key. */
    private static Duration retryInterval(Map<?, ?> m, String key, Duration min) {
        String path = "delivery.retries." + key;
        Duration d = duration(m, path, key);
        if (d.compareTo(min) < 0 || d.compareTo(MAX_RETRY_INTERVAL) > 0) {
            throw new IllegalArgumentException(path + " must be from " + min + " to " + MAX_RETRY_INTERVAL + ", was " + d);
        }
        return d;
    }

    private static List<StepConfig> steps(Object node) {
        if (!(node instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("steps must be a non-empty list");
        List<StepConfig> steps = new ArrayList<>(list.size());
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "steps[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) throw new IllegalArgumentException(path + " must be an object");
            String type = string(m, path + ".type", "type");
            String name = string(m, path + ".name", "name");
            if (!names.add(name)) throw new IllegalArgumentException(path + ".name '" + name + "' is used twice; step names are unique in a flow");
            boolean retryable = Boolean.TRUE.equals(m.get("retryable"));
            Map<String, Object> config = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!STRUCTURAL.contains(key) && e.getValue() != null) config.put(key, e.getValue());
            }
            steps.add(new StepConfig(i + 1, type, name, retryable, config));
        }
        return steps;
    }

    private static Map<String, Object> section(Map<String, Object> doc, String key) {
        if (!(doc.get(key) instanceof Map<?, ?> m)) throw new IllegalArgumentException(key + " is required and must be an object");
        Map<String, Object> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    private static String string(Map<?, ?> m, String key) { return string(m, key, key); }

    private static String string(Map<?, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isEmpty()) throw new IllegalArgumentException(path + " is required and must be a non-empty string");
        return s;
    }

    /** A duration string ({@link RetryPolicy#parseDuration}); one that does not parse is refused naming its key. */
    private static Duration duration(Map<?, ?> m, String path, String key) {
        String text = string(m, path, key);
        try {
            return RetryPolicy.parseDuration(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    private static Optional<Long> number(Map<?, ?> m, String path, String key) {
        Object v = m.get(key);
        if (v == null) return Optional.empty();
        if (!(v instanceof Number n) || n.longValue() < 0 || (n instanceof Double || n instanceof Float)) {
            throw new IllegalArgumentException(path + " must be a non-negative integer, was " + v);
        }
        return Optional.of(n.longValue());
    }

    private static List<String> strings(Object node) {
        if (node == null) return List.of();
        if (!(node instanceof List<?> list)) throw new IllegalArgumentException("forwardHeaders must be a list of strings");
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }
}
