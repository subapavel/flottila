package dev.rail8.sim.violation;

/** Deliberate violation: the simulation depends on runtime (it runs over api, core and testkit only, D-143). */
public final class SimDependsOnRuntime {
    private SimDependsOnRuntime() {}
    public static Object runtime() { return new dev.rail8.runtime.violation.RuntimeFixture(); }
}
