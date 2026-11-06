package io.flottila.runtime.node;

import io.flottila.core.node.Membership;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-15a: what {@code PgMembership} does beyond the {@link MembershipContract} — the connection a node's membership opens for
 * itself ({@link PgMembership#connecting}) is opened again after it was closed under it, or after a connect that failed, so a
 * node renews again once its database is back; a close while the connect is pending ends once that connect has; a join that fails part-way leaves the node's row as it was (one transaction); {@link PgMembership#others}
 * counts the live members but the one asking; a closed membership fails every call, and closes only the connection it opened;
 * a reconnect (F-28) fails a renew hung on the connection it opened and the next renew runs on another, and never touches a
 * given connection.
 */
class PgMembershipConnectingIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration L = Duration.ofSeconds(30);
    static Vertx vertx;
    PgConnection admin;
    final List<PgMembership> opened = new ArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    static PgConnectOptions options() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void connect() {
        admin = PgConnection.connect(vertx, options()).await();
        admin.query("truncate nodes, work").execute().await();
    }

    @AfterEach
    void disconnect() {
        opened.forEach(m -> m.close().await());
        admin.close().await();
    }

    PgMembership connecting() {
        return connecting(options());
    }

    PgMembership connecting(PgConnectOptions to) {
        PgMembership m = PgMembership.connecting(vertx, to);
        opened.add(m);
        return m;
    }

    static <T> T await(Future<T> f) {
        try {
            return f.await(10, SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the membership's call failed", e);
        }
    }

    /** The cause a call failed with; an AssertionError when it succeeded. */
    static Throwable failure(Future<?> f) {
        try {
            f.await(10, SECONDS);
        } catch (Exception e) {
            return e;
        }
        return fail("the call succeeded: " + f.result());
    }

    /** Every session on the database but the admin's is ended, as a Postgres restart or a reset connection ends it. */
    void terminateTheOthers() {
        admin.query("select pg_terminate_backend(pid) from pg_stat_activity where datname = current_database() and pid <> pg_backend_pid()")
                .execute().await();
    }

    long epochOf(String node) {
        return admin.preparedQuery("select epoch from nodes where node = $1").execute(Tuple.of(node)).await().iterator().next().getLong(0);
    }

    @Test
    void aConnectingMembershipRenewsAgainAfterItsConnectionWasClosedUnderIt() {
        PgMembership m = connecting();
        long epoch = await(m.join("n-re", L));
        assertTrue(await(m.renew("n-re", epoch, L)).renewed(), "fixture: renews on its own connection");

        terminateTheOthers();
        boolean renewed = false;
        int failed = 0;
        for (int attempt = 0; attempt < 3 && !renewed; attempt++) {
            try {
                renewed = m.renew("n-re", epoch, L).await(10, SECONDS).renewed();
            } catch (Exception lost) {
                failed++;   // the renew on the connection that was ended, before its close was seen
            }
        }
        assertTrue(renewed, "a later renew opened a connection again and renewed (" + failed + " failed on the ended one)");
        assertTrue(failed <= 1, "at most the one call on the ended connection fails: " + failed);
    }

    @Test
    void aConnectThatFailsIsTriedAgainByTheNextCall() {
        admin.query("create role flaky login password 'right'").execute().await();
        try {
            admin.query("grant select, insert, update on nodes, work to flaky").execute().await();
            PgMembership m = connecting(options().setUser("flaky").setPassword("wrong"));
            failure(m.join("n-f", L));   // refused: the password is not the role's
            admin.query("alter role flaky password 'wrong'").execute().await();
            assertEquals(1L, await(m.join("n-f", L)), "the next call opened a connection again and joined");
            await(m.close());
        } finally {
            admin.query("drop owned by flaky").execute().await();
            admin.query("drop role flaky").execute().await();
        }
    }

    @Test
    void aCloseWhileTheConnectIsPendingEndsOnceTheConnectFails() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            java.util.concurrent.CompletableFuture<java.net.Socket> accepted = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    accepted.complete(silent.accept());   // accepts and never answers the startup message
                } catch (java.io.IOException e) {
                    accepted.completeExceptionally(e);
                }
            });
            PgMembership m = connecting(options().setHost("127.0.0.1").setPort(silent.getLocalPort()));
            Future<Membership.Renewal> renew = m.renew("n-p", 1, L);
            java.net.Socket peer = accepted.get(10, SECONDS);
            Future<Void> closed = m.close();
            assertFalse(closed.isComplete(), "the close waits for the connect in flight");
            peer.close();   // the server goes away mid-startup: the connect fails
            await(closed);
            failure(renew);
        }
    }

    @Test
    void aJoinThatFailsPartWayLeavesTheRowAsItWas() {
        PgMembership m = connecting();
        assertEquals(1L, await(m.join("n-j", L)));
        admin.query("alter table work rename to work_moved").execute().await();   // the join's second statement cannot run
        try {
            failure(m.join("n-j", L));
        } finally {
            admin.query("alter table work_moved rename to work").execute().await();
        }
        assertEquals(1L, epochOf("n-j"), "rolled back: the epoch the failed join bumped is not kept");
        assertEquals(2L, await(m.join("n-j", L)), "and the membership joins on");
    }

    @Test
    void othersCountsTheLiveMembersButTheOneAsking() {
        PgMembership m = connecting();
        await(m.join("n-a", L));
        assertEquals(0, await(m.others("n-a")), "alone");
        await(m.join("n-b", L));
        assertEquals(1, await(m.others("n-a")), "b is live");
        assertEquals(1, await(m.others("n-b")), "a is live");
        admin.query("update nodes set lease_until = now() - interval '1 second' where node = 'n-b'").execute().await();
        assertEquals(0, await(m.others("n-a")), "an expired b is not live");
    }

    @Test
    void aClosedMembershipFailsEveryCallAndClosesOnlyTheConnectionItOpened() {
        PgMembership own = connecting();
        await(own.join("n-c", L));
        await(own.close());
        assertInstanceOf(IllegalStateException.class, failure(own.renew("n-c", 1, L)), "closed: the call fails, no connection opens");
        await(own.close());   // twice: nothing more to close

        PgConnection given = PgConnection.connect(vertx, options()).await();
        try {
            PgMembership on = new PgMembership(given);
            await(on.join("n-g", L));
            await(on.close());
            assertInstanceOf(IllegalStateException.class, failure(on.join("n-g", L)));
            assertEquals(1, given.query("select 1").execute().await().size(), "the connection it was given stays its caller's, open");
        } finally {
            given.close().await();
        }
    }

    @Test
    void aReconnectFailsTheRenewHungOnItsConnectionAndTheNextRenewRunsOnAnother() {
        PgMembership m = connecting();
        m.reconnect();   // before any call: nothing to drop
        long epoch = await(m.join("n-h", L));
        PgConnection probe = PgConnection.connect(vertx, options()).await();
        var locker = admin.begin().await();
        boolean locked = true;
        try {
            admin.preparedQuery("select 1 from nodes where node = $1 for update").execute(Tuple.of("n-h")).await();   // the renew's row locked
            Future<Membership.Renewal> hung = m.renew("n-h", epoch, L);
            awaitLockWaiters(probe, 1);   // the renew waits on the row: no answer comes while the lock is held
            assertFalse(hung.isComplete(), "fixture: the renew is hung");

            m.reconnect();   // what the node lease asks half a lease after the renew's send
            failure(hung);   // its connection closed under it: the hung renew fails, the lock still held

            Future<Membership.Renewal> next = m.renew("n-h", epoch, L);
            locker.rollback().await();
            locked = false;
            assertTrue(await(next).renewed(), "the next renew, on a new connection, renews once the row is free");
        } finally {
            if (locked) locker.rollback().await();
            probe.close().await();
        }
    }

    @Test
    void aGivenConnectionIsNeverReconnected() {
        PgConnection given = PgConnection.connect(vertx, options()).await();
        try {
            PgMembership on = new PgMembership(given);
            long epoch = await(on.join("n-k", L));
            on.reconnect();
            assertTrue(await(on.renew("n-k", epoch, L)).renewed(), "the given connection still serves the membership");
            assertEquals(1, given.query("select 1").execute().await().size(), "and stays open: its caller's");
        } finally {
            given.close().await();
        }
    }

    /** Polls, on {@code probe}, until {@code n} sessions wait on a row lock in this database (bounded: an AssertionError after 10 s). */
    static void awaitLockWaiters(PgConnection probe, int n) {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = probe.query("select count(*)::int from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'")
                    .execute().await().iterator().next().getInteger(0);
            if (waiting >= n) return;
        }
        fail(n + " sessions never waited on the row lock: " + waiting);
    }
}
