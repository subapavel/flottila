package io.flottila.runtime.lease;

import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02L (D-083): what every {@link PollerLeases} guarantees. {@code hold(key, owner, ttl)} answers true when it takes a free or
 * lapsed key or renews one {@code owner} holds — held for ttl from the call — and false when another owner holds it;
 * {@code release(key, owner)} deletes the key only if {@code owner} holds it, at once. Keys are independent, and of many owners
 * racing for one free key exactly one wins. Run by {@code InMemoryPollerLeasesTest} (fake clock) and
 * {@code ValkeyPollerLeasesIT} (Testcontainers Valkey, short real ttls: a lapse is waited for, bounded, never slept).
 *
 * <p>The shape these cases assume (P2-02L test-author): {@code io.flottila.runtime.lease.PollerLeases} with
 * {@code Future<Boolean> hold(String key, String owner, Duration ttl)} and {@code Future<Void> release(String key, String owner)}.
 */
@Timeout(60)
public abstract class PollerLeasesContract {

    static final String KEY = "shop/feed/sftp://drop";
    static final String OTHER_KEY = "shop/sort/sftp://drop";
    static final String A = "node-a#1";
    static final String B = "node-b#1";
    static final String C = "node-c#1";
    static final Duration LONG = Duration.ofMinutes(5);

    /** The case's store: nothing held in it. */
    protected PollerLeases leases;

    /** A fresh store for one case, nothing held. */
    protected abstract PollerLeases emptyStore();

    /** A ttl short enough for {@link #lapse} to wait for (real time on a real store). */
    protected abstract Duration shortTtl();

    /** Returns once a lease on {@code key} taken just before with {@code ttl} has run out (moves a fake clock, or waits, bounded). */
    protected abstract void lapse(String key, Duration ttl);

    @BeforeEach
    void openTheStore() {
        leases = emptyStore();
    }

    static <T> T await(Future<T> f) {
        try {
            return f.await(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException stuck) {
            throw new AssertionError("no answer within 10 s", stuck);
        }
    }

    boolean hold(String key, String owner, Duration ttl) {
        return await(leases.hold(key, owner, ttl));
    }

    void release(String key, String owner) {
        await(leases.release(key, owner));
    }

    @Test
    void aFreeKeyIsHeldByItsFirstOwner() {
        assertTrue(hold(KEY, A, LONG), "a free key is taken");
    }

    @Test
    void aHeldKeyIsRefusedToAnotherOwner() {
        assertTrue(hold(KEY, A, LONG));

        assertFalse(hold(KEY, B, LONG), "another owner is refused while A holds");
        assertFalse(hold(KEY, C, LONG), "and so is a third: B's refused try took nothing");
        assertTrue(hold(KEY, A, LONG), "A still holds it");
    }

    /** A renew by the holder extends the lease: past the first ttl another owner is still refused. */
    @Test
    void theHolderHoldsAgainAndExtends() {
        String sentinel = "sentinel/" + KEY;
        assertTrue(hold(KEY, A, shortTtl()));
        assertTrue(hold(sentinel, A, shortTtl()), "fixture: a key that is not renewed, taken at the same moment");

        assertTrue(hold(KEY, A, LONG), "the holder holds again");
        lapse(sentinel, shortTtl());

        assertTrue(hold(sentinel, B, LONG), "fixture: the first ttl has passed (the key held with it alone lapsed)");
        assertFalse(hold(KEY, B, LONG), "the renewed key is still A's");
    }

    @Test
    void aLapsedLeaseGoesToAnotherOwner() {
        assertTrue(hold(KEY, A, shortTtl()));

        lapse(KEY, shortTtl());

        assertTrue(hold(KEY, B, LONG), "a lapsed lease is free");
        assertFalse(hold(KEY, A, LONG), "and A, which let it lapse, is refused now");
    }

    @Test
    void releaseFreesTheKeyAtOnce() {
        assertTrue(hold(KEY, A, LONG));

        release(KEY, A);

        assertTrue(hold(KEY, B, LONG), "free at once, though its ttl had minutes to run");
    }

    @Test
    void releaseByANonHolderLeavesItInPlace() {
        assertTrue(hold(KEY, A, LONG));

        release(KEY, B);
        release(OTHER_KEY, B);   // a key nobody holds: nothing to do

        assertFalse(hold(KEY, C, LONG), "B's release did not free A's lease");
        assertTrue(hold(KEY, A, LONG), "A still holds it");
    }

    @Test
    void keysAreIndependent() {
        assertTrue(hold(KEY, A, LONG));

        assertTrue(hold(OTHER_KEY, B, LONG), "another key is free whoever holds the first");
        release(KEY, A);
        assertFalse(hold(OTHER_KEY, C, LONG), "releasing the first key left the other in place");
        assertTrue(hold(KEY, C, LONG));
    }

    /** Sixteen owners hold one free key at the same moment, each from its own thread: exactly one is answered true. */
    @Test
    void aRaceForAFreeKeyHasOneWinner() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String owner = "node-" + i + "#1";
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                answers.add(leases.hold(KEY, owner, LONG));
            }));
        }
        go.countDown();
        for (Thread t : threads) assertTrue(t.join(Duration.ofSeconds(10)), "every owner asked");

        assertEquals(16, answers.size());
        assertEquals(1, answers.stream().filter(PollerLeasesContract::await).count(), "exactly one owner holds the key");
    }
}
