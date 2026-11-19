package dev.rail8.cli.violation;

/** Deliberate violation: a cli class names a shipped step, which the CLI finds as a module instead. */
public final class CliNamesAStep {
    private CliNamesAStep() {}
    public static Class<?> step() { return dev.rail8.steps.jq.JqStepFactory.class; }
}
