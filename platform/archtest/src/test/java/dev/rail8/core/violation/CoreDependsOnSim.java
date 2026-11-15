package dev.rail8.core.violation;

/** Deliberate violation: core depends on the simulation (nothing depends on rail8-sim, D-143). */
public final class CoreDependsOnSim {
    private CoreDependsOnSim() {}
    public static Object sim() { return new dev.rail8.sim.violation.SimFixture(); }
}
