package dev.rail8.steps.mapping.violation;

/** Deliberate violation: the mapping step reaches past the embedding API into the engine's facade. */
public final class MappingStepUsesTheEngineFacade {
    private MappingStepUsesTheEngineFacade() {}
    public static Class<?> facade() { return dev.rail8.mapping.api.EngineFacade.class; }
}
