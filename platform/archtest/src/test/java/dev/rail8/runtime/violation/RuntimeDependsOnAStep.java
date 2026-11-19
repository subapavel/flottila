package dev.rail8.runtime.violation;

/** Deliberate violation: a runtime class names a shipped step, which the node finds as a module instead. */
public final class RuntimeDependsOnAStep {
    private RuntimeDependsOnAStep() {}
    public static Class<?> step() { return dev.rail8.steps.validate.XsdValidate.class; }
}
