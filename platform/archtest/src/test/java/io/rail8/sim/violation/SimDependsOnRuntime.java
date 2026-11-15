package io.rail8.sim.violation;

/** Deliberate violation: the simulation depends on runtime (it runs over api, core and testkit only, D-143). */
public final class SimDependsOnRuntime {
    private SimDependsOnRuntime() {}
    public static Object runtime() { return new io.rail8.runtime.violation.RuntimeFixture(); }
}
