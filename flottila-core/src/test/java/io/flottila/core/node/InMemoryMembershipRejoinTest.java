package io.flottila.core.node;

import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-15a (D-101 (1), D-112): a restarted node's own unheld items wait for its previous incarnation's lease to end — unless a
 * peer released that incarnation (its lease ended before the release), and never earlier than the latest lease end any restart
 * found, whether a later incarnation's lease ends earlier (a shorter lease) or later; an item the current incarnation was handed
 * never waits for it.
 */
class InMemoryMembershipRejoinTest {

    static final Duration L = Duration.ofSeconds(30);
    static final Duration ITEM_LEASE = Duration.ofSeconds(5);

    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-05T08:00:00Z"));
    final InMemoryMembership members = new InMemoryMembership(clock);
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock, members);

    List<Long> claimBy(String node) {
        return queue.claim(node, "t", 10, ITEM_LEASE).await().stream().map(WorkQueue.Item::seq).toList();
    }

    long enqueueAndClaim(String id, String node) {
        queue.enqueue(id, "t", 1, null).await();
        return queue.claim(node, "t", 1, ITEM_LEASE).await().get(0).seq();
    }

    @Test
    void aReleasedNodeThatJoinsAgainRerunsItsItemsAtOnce() {
        long epoch = members.join("a", L).await();
        long x = enqueueAndClaim("x", "a");
        clock.advance(L.plusSeconds(1));
        assertEquals(List.of("a"), members.release(List.of("a")).await(), "fixture: a peer released the expired a");
        assertEquals(false, members.renew("a", epoch, L).await().renewed(), "fixture: released");

        assertEquals(epoch + 1, members.join("a", L).await(), "a restarts");
        assertEquals(List.of(x), claimBy("a"), "the released incarnation's fence is long gone: its item reruns at once");
    }

    @Test
    void aSecondRestartWithAShorterLeaseKeepsTheFirstIncarnationsLaterLeaseEnd() {
        members.join("a", Duration.ofSeconds(100)).await(); // T: leased until T+100 s
        long x = enqueueAndClaim("x", "a");
        clock.advance(Duration.ofSeconds(1));
        members.join("a", L).await();                       // T+1 s: the first incarnation's lease ends T+100 s; leased until T+31 s
        clock.advance(Duration.ofSeconds(1));
        members.join("a", L).await();                       // T+2 s: the second's lease ends T+31 s, earlier: x still waits until T+100 s

        clock.advance(Duration.ofSeconds(98).minusMillis(1));   // T+100 s − 1 ms: long past the second's lease end
        assertEquals(List.of(), claimBy("a"), "the item the first incarnation was handed waits for that incarnation's lease end");
        clock.advance(Duration.ofMillis(1));
        assertEquals(List.of(x), claimBy("a"), "at the first incarnation's lease end the item reruns");
    }

    @Test
    void anItemTheCurrentIncarnationWasHandedIsNotHeldBackByTheOldFence() {
        members.join("a", L).await();                       // T: leased until T+30 s
        clock.advance(Duration.ofSeconds(1));
        members.join("a", L).await();                       // T+1 s: restarted, the old lease ends T+30 s
        long x = enqueueAndClaim("x", "a");                 // handed to this incarnation
        clock.advance(ITEM_LEASE.plusSeconds(1));           // T+7 s: its item lease ran out, the old lease not ended

        assertEquals(List.of(x), claimBy("a"), "only the previous incarnation's items wait for its lease end");
    }

    @Test
    void aSecondRestartWithTheSameLeaseMovesTheWaitToTheLaterLeaseEnd() {
        members.join("a", L).await();                       // T: leased until T+30 s
        long x = enqueueAndClaim("x", "a");
        clock.advance(Duration.ofSeconds(1));
        members.join("a", L).await();                       // T+1 s: the first incarnation's lease ends T+30 s; leased until T+31 s
        clock.advance(Duration.ofSeconds(1));
        members.join("a", L).await();                       // T+2 s: the second's lease end, T+31 s, is later

        clock.advance(Duration.ofSeconds(29).minusMillis(1));   // T+31 s − 1 ms: past the first lease end, before the second's
        assertEquals(List.of(), claimBy("a"), "past the first incarnation's lease end, before the second's");
        clock.advance(Duration.ofMillis(1));
        assertEquals(List.of(x), claimBy("a"), "at the later lease end the item reruns");
    }
}
