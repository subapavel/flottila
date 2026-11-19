package dev.rail8.steps.mapping;

import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The mapping module: the {@code mapping} step.
 *
 * @since 0.1
 */
public final class MappingPlugin implements Plugin {

    @Override public String name() { return "mapping"; }

    @Override public String api() { return API; }

    /** The mapping step. */
    @Override public List<StepFactory> steps() { return List.of(new MappingStepFactory()); }
}
