package dev.rail8.steps.violation;

/** Deliberate violation: a step depends on core (steps depend on api only). */
public final class StepDependsOnCore {
    private StepDependsOnCore() {}
    public static Object core() { return new dev.rail8.core.violation.CoreFixture(); }
}
