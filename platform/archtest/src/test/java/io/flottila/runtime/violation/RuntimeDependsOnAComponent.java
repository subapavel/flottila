package io.flottila.runtime.violation;

/** Deliberate violation: a runtime class other than Main names a shipped component (F-4: only Main wires them). */
public final class RuntimeDependsOnAComponent {
    private RuntimeDependsOnAComponent() {}
    public static Class<?> component() { return io.flottila.components.http.HttpComponent.class; }
}
