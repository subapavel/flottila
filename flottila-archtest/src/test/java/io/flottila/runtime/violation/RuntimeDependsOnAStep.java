package io.flottila.runtime.violation;

/** Deliberate violation: a runtime class other than Main names a shipped step (F-4: only Main wires them). */
public final class RuntimeDependsOnAStep {
    private RuntimeDependsOnAStep() {}
    public static Class<?> step() { return io.flottila.steps.validate.XsdValidate.class; }
}
