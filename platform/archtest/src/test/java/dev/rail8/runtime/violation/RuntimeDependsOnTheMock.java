package dev.rail8.runtime.violation;

/** Deliberate violation: a runtime class names the mock component (a deployed node refuses mock bindings, §11; F-4). */
public final class RuntimeDependsOnTheMock {
    private RuntimeDependsOnTheMock() {}
    public static Class<?> mock() { return dev.rail8.components.mock.MockComponent.class; }
}
