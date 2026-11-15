package io.flottila.runtime.node;

import io.flottila.core.node.InMemoryMembership;
import io.flottila.core.node.Membership;
import io.flottila.testkit.FakeClock;

import java.time.Instant;

/** P2-15a: core's {@code InMemoryMembership} on a fake clock passes the {@link MembershipContract}. */
class InMemoryMembershipContractTest extends MembershipContract {

    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-05T08:00:00Z"));
    final InMemoryMembership members = new InMemoryMembership(clock);

    @Override Membership membership() { return members; }

    @Override Membership second() { return members; }

    @Override void pastTheLease() { clock.advance(L.plusSeconds(1)); }
}
