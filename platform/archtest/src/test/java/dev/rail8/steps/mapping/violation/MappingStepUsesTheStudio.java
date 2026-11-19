package dev.rail8.steps.mapping.violation;

/** Allowed: the mapping step uses the mapping studio. */
public final class MappingStepUsesTheStudio {
    private MappingStepUsesTheStudio() {}
    public static Class<?> studio() { return dev.rail8.mapping.studio.Studio.class; }
}
