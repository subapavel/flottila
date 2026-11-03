package io.flottila.core.violation;

/** Deliberate violation: core depends on the simulation (nothing depends on flottila-sim, D-101). */
public final class CoreDependsOnSim {
    private CoreDependsOnSim() {}
    public static Object sim() { return new io.flottila.sim.violation.SimFixture(); }
}
