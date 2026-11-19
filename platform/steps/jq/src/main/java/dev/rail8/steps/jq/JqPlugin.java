package dev.rail8.steps.jq;

import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;

import java.util.List;

/**
 * The jq module: the {@code jq} step. Its one setting, {@code RAIL8_JQ_MAX_RECURSION_DEPTH}, is the recursion depth of a jq step
 * whose flow sets none: 10 000 unset, from 1 to {@link JqStepFactory#MAX_RECURSION_DEPTH}.
 *
 * @since 0.1
 */
public final class JqPlugin implements Plugin {

    /** The recursion depth of a jq step when neither its flow nor the setting sets one. */
    static final int MAX_RECURSION_DEPTH_DEFAULT = 10_000;

    @Override public String name() { return "jq"; }

    @Override public String api() { return API; }

    /**
     * The jq step, its depth the setting {@code MAX_RECURSION_DEPTH}.
     *
     * @throws IllegalArgumentException the setting is no whole number from 1 to {@link JqStepFactory#MAX_RECURSION_DEPTH}
     */
    @Override
    public List<StepFactory> steps(PluginSettings settings) {
        int depth = (int) settings.whole("MAX_RECURSION_DEPTH", MAX_RECURSION_DEPTH_DEFAULT, 1, JqStepFactory.MAX_RECURSION_DEPTH);
        return List.of(new JqStepFactory(depth));
    }
}
