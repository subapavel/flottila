package io.flottila.runtime.node;

import io.flottila.core.node.Membership;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a acceptance 2 (D-101 (1)): what every {@link Membership} does — the in-memory one in core and {@code PgMembership} on
 * its dedicated connection pass the same cases. A join bumps the node's epoch; a renew with an older epoch is superseded (0 rows);
 * a renew answers the live members and the expired peers; a release releases only expired peers, and a released node's next
 * renew is superseded; a renew racing a release over two connections — exactly one of them wins.
 *
 * <p>The API assumed (P2-15a, test-author): {@code join(node, lease)} → {@code Future<Long>} epoch;
 * {@code renew(node, epoch, lease)} → {@code Future<Renewal(boolean renewed, int live, List<String> expired)>} — live counts
 * the live members, the renewing one included; expired names the other members whose lease ran out and that nobody released;
 * a row whose lease ran out and that nobody released renews (a database outage takes nobody over);
 * {@code release(List<String> peers)} → {@code Future<List<String>>}, the peers it released.
 */
abstract class MembershipContract {

    static final Duration L = Duration.ofSeconds(30);

    /** The membership under test, on its first connection. */
    abstract Membership membership();

    /** The same membership's store through a second connection (in memory: the same instance). */
    abstract Membership second();

    /** Every lease held now runs out (in memory: the clock moves past L; Postgres: every lease_until moves back by more than L). */
    abstract void pastTheLease();

    static <T> T await(Future<T> f) {
        try {
            return f.await(10, SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the membership's call failed", e);
        }
    }

    @Test
    void aJoinBumpsTheEpoch() {
        long first = await(membership().join("n-join", L));
        long again = await(membership().join("n-join", L));
        assertEquals(first + 1, again, "a rejoin of the same node bumps its epoch by one");
        assertEquals(again + 1, await(second().join("n-join", L)), "through another connection too: one row per node");
        assertTrue(await(membership().renew("n-join", again + 1, L)).renewed(), "the newest epoch renews");
    }

    @Test
    void anOldEpochRenewIsSuperseded() {
        long old = await(membership().join("n-epoch", L));
        long current = await(membership().join("n-epoch", L));   // the node restarted
        Membership.Renewal stale = await(membership().renew("n-epoch", old, L));
        assertFalse(stale.renewed(), "the previous incarnation's renew updates nothing");
        assertTrue(await(membership().renew("n-epoch", current, L)).renewed(), "the current incarnation is untouched and renews");
    }

    @Test
    void noReleaseBeforeExpiry() {
        long a = await(membership().join("n-a", L));
        long b = await(membership().join("n-b", L));
        assertEquals(List.of(), await(second().release(List.of("n-a"))), "a live peer is not released");
        assertTrue(await(membership().renew("n-a", a, L)).renewed(), "and renews on");

        pastTheLease();
        assertTrue(await(membership().renew("n-b", b, L)).renewed(), "b, expired and not released, renews");
        assertEquals(List.of("n-a"), await(second().release(List.of("n-a", "n-b"))), "only the expired a is released; b renewed");
        assertFalse(await(membership().renew("n-a", a, L)).renewed(), "a released node's next renew is superseded");
        assertEquals(List.of(), await(second().release(List.of("n-a"))), "a released row is released once");
    }

    @Test
    void aRenewNamesTheExpiredPeersAndCountsTheLiveOnes() {
        long a = await(membership().join("n-1", L));
        await(membership().join("n-2", L));
        long c = await(membership().join("n-3", L));
        Membership.Renewal all = await(membership().renew("n-1", a, L));
        assertEquals(3, all.live(), "three live members");
        assertEquals(List.of(), all.expired());

        pastTheLease();
        Membership.Renewal alone = await(membership().renew("n-1", a, L));
        assertTrue(alone.renewed());
        assertEquals(1, alone.live(), "n-1 alone is live after its renew");
        assertEquals(Set.of("n-2", "n-3"), Set.copyOf(alone.expired()), "the expired peers, not the renewing node");
        assertEquals(List.of("n-2"), await(membership().release(List.of("n-2"))));
        assertEquals(List.of("n-3"), await(membership().renew("n-1", a, L)).expired(), "a released peer is no longer named");
        assertTrue(await(membership().renew("n-3", c, L)).renewed(), "fixture: n-3 comes back");
        Membership.Renewal two = await(membership().renew("n-1", a, L));
        assertEquals(2, two.live());
        assertEquals(List.of(), two.expired());
    }

    @Test
    void aRenewRacingAReleaseOnTwoConnectionsExactlyOneWins() {
        // in sequence, both orders: the release first supersedes the renew; the renew first leaves nothing to release
        long e1 = await(membership().join("n-race", L));
        pastTheLease();
        assertEquals(List.of("n-race"), await(second().release(List.of("n-race"))));
        assertFalse(await(membership().renew("n-race", e1, L)).renewed(), "released first: the renew loses");
        long e2 = await(membership().join("n-race", L));
        pastTheLease();
        assertTrue(await(membership().renew("n-race", e2, L)).renewed(), "renewed first");
        assertEquals(List.of(), await(second().release(List.of("n-race"))), "then the release finds it live: the release loses");

        // at once, on two connections
        for (int round = 0; round < 40; round++) {
            long epoch = await(membership().join("n-race", L));
            pastTheLease();
            Future<Membership.Renewal> renew = membership().renew("n-race", epoch, L);
            Future<List<String>> release = second().release(List.of("n-race"));
            boolean renewed = await(renew).renewed();
            boolean released = await(release).equals(List.of("n-race"));
            assertTrue(renewed ^ released, "round " + round + ": exactly one wins (renewed " + renewed + ", released " + released + ")");
            assertEquals(renewed, await(membership().renew("n-race", epoch, L)).renewed(),
                    "round " + round + ": the outcome sticks — a winning renew renews on, a winning release supersedes the next renew");
        }
    }
}
