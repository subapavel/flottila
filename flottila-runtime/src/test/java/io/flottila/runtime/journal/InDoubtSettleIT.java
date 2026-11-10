package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An in-doubt save settled by its {@code pg_xact_status} (CORE-4d; the implementer's), observed through the store: a COMMIT
 * that landed after its reply was lost settles as committed — the body's local copy goes, as for a commit answered —; one that
 * never reached the server settles as rolled back — the body stays local. Seam: {@link PgWire} cuts each COMMIT's reply.
 */
@Timeout(240)
class InDoubtSettleIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool relayed;
    JournalWorld a;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() throws Exception {
        db = CaseDatabase.create(pg, vertx, "settle_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        relayed = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        a = JournalWorld.open(vertx, relayed, db.pool(), Files.createDirectories(tmp.resolve("node-a")), "node-a");
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        wire.close();
        relayed.close().await();
        db.close();
    }

    /**
     * A save under partition key {@code partition} whose COMMIT reply is cut: failed, open, its body local. P2-16a (D-121 A3): its
     * transaction, open until the case decides it, holds the key's lock, so the next lost reply goes under another key.
     */
    PgWire.Held lostReply(String id, String partition) throws Exception {
        PgWire.Held held = wire.holdNext(PgWire.query("COMMIT"));
        Envelope e = a.arrive(id, bytes(3_000, id.hashCode()));
        Future<String> save = a.intake.save(e, JournalWorld.ingress(e), JournalWorld.FLOW, partition, null, false, undecided());
        held.awaitReached();
        held.cut();
        assertTrue(settled(save).failed(), "fixture: the save whose commit reply was lost fails");
        assertTrue(a.blobs.location(BlobKey.body(id, 0)).isPresent(), "fixture: its body is local while its outcome is unknown");
        return held;
    }

    @Test
    void aCommitThatLandedSettlesAsCommittedAndOneThatDidNotAsRolledBack() throws Exception {
        PgWire.Held landed = lostReply("m-landed", "K-1");
        PgWire.Held lost = lostReply("m-lost", "K-2");
        assertEquals(2, List.copyOf(a.intake.openSaves()).size(), "fixture: both saves open, their outcome unknown");

        landed.commitAfterCut();
        lost.abort();
        SegmentSweepIT.until(() -> count(db.pool(), "select count(*) from work where id = 'm-landed'") == 1
                        && count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'") == 0,
                () -> "the landed COMMIT visible and the lost one's session gone");

        assertEquals(List.of(), List.copyOf(a.intake.openSaves()), "both outcomes known: no open save left");
        assertTrue(a.blobs.location(BlobKey.body("m-landed", 0)).isEmpty(), "committed: the body's local copy is gone, as for an answered commit");
        assertTrue(a.blobs.location(BlobKey.body("m-lost", 0)).isPresent(), "rolled back: the body stays local");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-lost'"), "fixture: the lost COMMIT left no row");
    }
}
