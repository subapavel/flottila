package dev.rail8.runtime.violation;

/** Deliberate violation: a runtime class names a shipped component, which the node finds as a module instead. */
public final class RuntimeDependsOnAComponent {
    private RuntimeDependsOnAComponent() {}
    public static Class<?> component() { return dev.rail8.components.http.HttpComponent.class; }
}
