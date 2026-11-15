package io.flottila.steps.violation;

/** Deliberate violation: a step depends on core (steps depend on api only). */
public final class StepDependsOnCore {
    private StepDependsOnCore() {}
    public static Object core() { return new io.flottila.core.violation.CoreFixture(); }
}
