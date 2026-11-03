package io.flottila.sim.violation;

/** Deliberate violation: the simulation depends on runtime (it runs over api, core and testkit only, D-101). */
public final class SimDependsOnRuntime {
    private SimDependsOnRuntime() {}
    public static Object runtime() { return new io.flottila.runtime.violation.RuntimeFixture(); }
}
