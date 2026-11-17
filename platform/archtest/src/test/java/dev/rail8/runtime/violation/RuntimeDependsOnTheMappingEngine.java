package dev.rail8.runtime.violation;

/** Deliberate violation: a platform module other than the mapping step depends on the mapping engine. */
public final class RuntimeDependsOnTheMappingEngine {
    private RuntimeDependsOnTheMappingEngine() {}
    public static Class<?> engine() { return dev.rail8.mapping.embed.Mapping.class; }
}
