package io.flottila.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of a flow's {@code steps} as a {@link StepFactory} sees it.
 *
 * @param index the step's number in the flow: ingress is 0, the n-th configured step is n (ARCHITECTURE §5)
 * @param type step type, resolved to a {@link StepFactory} or a built-in of the builder
 * @param name step name, unique in the flow; the label of the timeline
 * @param retryable whether the flow's retry policy applies to this step
 * @param config the step's own keys, everything but {@code type}, {@code name} and {@code retryable}
 * @since 0.1
 */
public record StepConfig(int index, String type, String name, boolean retryable, Map<String, Object> config) {

    public StepConfig {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (index < 1) throw new IllegalArgumentException("step index starts at 1, was " + index);
        config = Map.copyOf(config);
    }

    /** The value under {@code key} when it is a non-empty string. */
    public Optional<String> string(String key) {
        return config.get(key) instanceof String s && !s.isEmpty() ? Optional.of(s) : Optional.empty();
    }

    /** The value under {@code key} as a non-empty string, or an {@link IllegalArgumentException} naming the step and the key. */
    public String requireString(String key) {
        return string(key).orElseThrow(() -> new IllegalArgumentException(this + ": '" + key + "' must be a non-empty string"));
    }

    /** {@code step '<name>' (steps[<index-1>], <type>)} — the way every build error names a step. */
    @Override public String toString() { return "step '" + name + "' (steps[" + (index - 1) + "], " + type + ")"; }
}
