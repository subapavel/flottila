package dev.rail8.runtime.node;

import dev.rail8.core.node.InMemoryMembership;
import dev.rail8.core.node.Membership;
import dev.rail8.testkit.FakeClock;

import java.time.Instant;

/** P2-15a: core's {@code InMemoryMembership} on a fake clock passes the {@link MembershipContract}. */
class InMemoryMembershipContractTest extends MembershipContract {

    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-05T08:00:00Z"));
    final InMemoryMembership members = new InMemoryMembership(clock);

    @Override Membership membership() { return members; }

    @Override Membership second() { return members; }

    @Override void pastTheLease() { clock.advance(L.plusSeconds(1)); }
}
