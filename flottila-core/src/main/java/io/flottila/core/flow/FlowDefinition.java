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
 * that passed the schema never fails here. {@code recording} is the flow's policy (default: all, keep); a step's own
 * {@code recording} stays in its config and overrides it in the builder. {@code saveTimeout} bounds an at-least-once
 * flow's durable save ({@code delivery.saveTimeout}, default {@link #DEFAULT_SAVE_TIMEOUT}); a body limit above the
 * in-flight byte limit is refused, since such a body could never be admitted (D-049 f).
 *
 * @since 0.1
 */
public record FlowDefinition(String id, String version, String fromEndpoint, String deliveryMode, Duration saveTimeout, long maxBodyBytes,
                             Optional<Long> maxInFlight, Optional<Long> maxInFlightBytes, Optional<String> partitionBy,
                             List<String> forwardHeaders, RetryPolicy retry, RecordingPolicy recording, List<StepConfig> steps) {

    /** Keys of a step entry that are the step's shape, not its configuration. */
    private static final Set<String> STRUCTURAL = Set.of("type", "name", "retryable");

    /** How long an at-least-once save may take before the sender is told to retry, when the flow names nothing (ARCHITECTURE §4). */
    public static final Duration DEFAULT_SAVE_TIMEOUT = Duration.ofSeconds(5);

    public FlowDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(fromEndpoint, "from.endpoint");
        Objects.requireNonNull(deliveryMode, "delivery.mode");
        Objects.requireNonNull(saveTimeout, "delivery.saveTimeout");
        if (saveTimeout.compareTo(Duration.ZERO) <= 0) throw new IllegalArgumentException("delivery.saveTimeout must be positive, was " + saveTimeout);
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(recording, "recording");
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("limits.maxBodyBytes must be positive, was " + maxBodyBytes);
        if (maxInFlightBytes.isPresent() && maxBodyBytes > maxInFlightBytes.get()) {
            throw new IllegalArgumentException("limits.maxBodyBytes " + maxBodyBytes + " exceeds limits.maxInFlightBytes " + maxInFlightBytes.get()
                    + ": such a body could never be admitted");
        }
        forwardHeaders = List.copyOf(forwardHeaders);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("steps must name at least one step");
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
                number(limits, "limits.maxBodyBytes", "maxBodyBytes").orElseThrow(() -> new IllegalArgumentException("limits.maxBodyBytes is required")),
                number(limits, "limits.maxInFlight", "maxInFlight"), number(limits, "limits.maxInFlightBytes", "maxInFlightBytes"),
                Optional.ofNullable(doc.get("partitionBy")).map(Object::toString),
                strings(doc.get("forwardHeaders")), retry(doc.get("retry")), RecordingPolicy.from(doc.get("recording"), RecordingPolicy.DEFAULT), steps(doc.get("steps")));
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
