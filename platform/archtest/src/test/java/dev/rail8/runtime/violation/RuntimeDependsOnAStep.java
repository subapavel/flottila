package dev.rail8.runtime.violation;

/** Deliberate violation: a runtime class other than Main names a shipped step (F-4: only Main wires them). */
public final class RuntimeDependsOnAStep {
    private RuntimeDependsOnAStep() {}
    public static Class<?> step() { return dev.rail8.steps.validate.XsdValidate.class; }
}
