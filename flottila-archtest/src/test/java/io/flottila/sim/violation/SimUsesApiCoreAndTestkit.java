package io.flottila.sim.violation;

import java.time.Instant;

/** Allowed: the simulation names api, core, testkit and its own classes. */
public final class SimUsesApiCoreAndTestkit {
    private SimUsesApiCoreAndTestkit() {}
    public static Object[] uses() {
        return new Object[] {
                new io.flottila.api.Ack.Accepted("m-1"),
                new io.flottila.core.violation.CoreFixture(),
                io.flottila.testkit.FakeClock.at(Instant.EPOCH),
                new SimFixture()};
    }
}
