package io.flottila.runtime.violation;

/** Deliberate violation: a runtime class names the mock component (a deployed node refuses mock bindings, §11; F-4). */
public final class RuntimeDependsOnTheMock {
    private RuntimeDependsOnTheMock() {}
    public static Class<?> mock() { return io.flottila.components.mock.MockComponent.class; }
}
