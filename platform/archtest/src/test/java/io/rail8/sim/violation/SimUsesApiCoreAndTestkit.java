package io.rail8.sim.violation;

import java.time.Instant;

/** Allowed: the simulation names api, core, testkit and its own classes. */
public final class SimUsesApiCoreAndTestkit {
    private SimUsesApiCoreAndTestkit() {}
    public static Object[] uses() {
        return new Object[] {
                new io.rail8.api.Ack.Accepted("m-1"),
                new io.rail8.core.violation.CoreFixture(),
                io.rail8.testkit.FakeClock.at(Instant.EPOCH),
                new SimFixture()};
    }
}
