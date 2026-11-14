package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.Workers;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.runtime.journal.JournalWorld.FLOW;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.opened;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b review r1 V4 (implementer, round 3): an announce that throws in a <em>group</em> of node-a's intake (D-121 (b) B4 (iii)) is
 * isolated per member, after the group's commit — its later members are still announced and a committed member stays committed.
 * Node-a ({@link JournalWorld} on a pool relayed by {@link PgWire}) saves through a grouping {@link PgIntake} (one group in flight,
 * its window on a fake timer never advanced: members go when the slot frees) whose {@link WorkSignals} throws on its first call and
 * records every later one; node-b's journal holds the kept bodies of the members written plain. A plug (a save of node-a held at
 * its COMMIT) gathers the members called meanwhile into one group.
 */
@Timeout(240)
class PgIntakeAnnounceGroupIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool relayed;
    Pool bPool;
    JournalWorld a;
    JournalWorld b;
    PgIntake intake;
    final AtomicInteger tried = new AtomicInteger();
    final List<String> announced = new CopyOnWriteArrayList<>();
    static final IllegalStateException REFUSAL = new IllegalStateException("the work events' timer refused the window (test)");

    /** Throws {@link #REFUSAL} on its first call, records {@code target flow} on every later one. */
    final WorkSignals firstThrows = (target, flow) -> {
        if (tried.incrementAndGet() == 1) throw REFUSAL;
        announced.add(target + " " + flow);
    };

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
        db = CaseDatabase.create(pg, vertx, "announce_group_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        relayed = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        bPool = PgBuilder.pool().connectingTo(db.options()).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        a = JournalWorld.open(vertx, relayed, db.pool(), Files.createDirectories(tmp.resolve("node-a")), "node-a");
        b = JournalWorld.open(vertx, bPool, db.pool(), Files.createDirectories(tmp.resolve("node-b")), "node-b");
        FakeTimer window = new FakeTimer(FakeClock.at(Instant.parse("2026-11-11T10:00:00Z")));
        intake = new PgIntake(a.pool, a.blobs, a.messages, a.queue, new Workers(), window,
                new NodeConfig.IntakeGroup(64, 1L << 20, Duration.ofMillis(50), 1), firstThrows);
        relayed.query("select 1").execute().await();
    }

    @AfterEach
    void close() throws Exception {
        wire.close();   // first: a held reply never hangs the pool's close
        if (a != null) a.close();
        if (b != null) b.close();
        relayed.close().await();
        bPool.close().await();
        db.close();
    }

    /** {@code e}'s save of flow {@code orders}, no key, called on a virtual thread; returns once the call returned. */
    Future<String> call(Envelope e) throws Exception {
        return OnVirtualThread.join(OnVirtualThread.start(() -> intake.save(e, JournalWorld.ingress(e), FLOW, null, null, false, undecided())));
    }

    /** Message {@code id} arriving at node-a with a body kept already in node-b's journal: node-a's store holds no copy of it. */
    Envelope keptOnB(String id) {
        Envelope onB = b.arrive(id, bytes(2_000, id.hashCode()));
        BlobKey key = onB.body().key();
        assertTrue(settled(b.blobs.persist(key)).succeeded(), "fixture: " + id + " durable in node-b's journal");
        return Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                Body.of(a.blobs, key, onB.body().hash(), onB.body().size()));
    }

    /** Saves {@code members} as one group — a plug of node-a's own held at its COMMIT while they are called — and answers them. */
    List<Future<String>> oneGroup(List<Envelope> members) throws Exception {
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> plug = call(a.arrive("m-plug", bytes(1_000, 3)));
        commit.awaitReached();
        List<Future<String>> saves = new ArrayList<>();
        for (Envelope m : members) saves.add(call(m));
        for (Future<String> s : saves) assertFalse(s.isComplete(), "fixture: every member waits behind the plug: " + s);
        commit.release();
        assertTrue(settled(plug).succeeded(), "the plug saved: " + plug.cause());
        for (Future<String> s : saves) settled(s);
        return saves;
    }

    String bodyNode(String id) {
        for (Row r : db.pool().preparedQuery("select body_node from work where id = $1").execute(Tuple.of(id)).await()) return r.getString(0);
        return null;
    }

    /**
     * Two members of one group whose kept bodies lie on node-b, each written plain and announced to node-b after the commit: the
     * first announce throws, the other member is still announced — and both saves answer their ids.
     */
    @Test
    void aGroupWhoseFirstAnnounceThrowsStillAnnouncesItsLaterMembers() throws Exception {
        List<Future<String>> saves = oneGroup(List.of(keptOnB("m-far-1"), keptOnB("m-far-2")));

        assertEquals(List.of("m-far-1", "m-far-2"), saves.stream().map(s -> s.succeeded() ? s.result() : "failed: " + s.cause()).toList(),
                "each member answered its id despite the throw");
        assertEquals("node-b", bodyNode("m-far-1"), "fixture: written plain, its body on node-b");
        assertEquals("node-b", bodyNode("m-far-2"));
        assertEquals(2, tried.get(), "an announce per member: the first threw, the next one was still made");
        assertEquals(List.of("node-b " + FLOW), announced, "the member after the throw announced to its body's node");
    }

    /**
     * A member of node-a's own (its body forced into node-a's journal) committed in the same group as a member whose announce throws:
     * its save answers its id and it stays committed — out of the open saves, its work row naming its record in node-a's journal and its body read back.
     */
    @Test
    void aCommittedMemberOfAGroupWhoseAnnounceThrowsStaysCommitted() throws Exception {
        Buffer own = bytes(2_000, 41);
        Envelope near = a.arrive("m-near", own);
        List<Future<String>> saves = oneGroup(List.of(keptOnB("m-far"), near));

        assertTrue(saves.get(0).succeeded(), "m-far answered: " + saves.get(0).cause());
        assertTrue(saves.get(1).succeeded(), "m-near answered: " + saves.get(1).cause());
        assertEquals("m-near", saves.get(1).result());
        assertEquals(1, tried.get(), "witness: m-far's announce was made and threw; m-near is node-a's own, announced nowhere");
        assertEquals(List.of(), List.copyOf(a.blobs.openSaves()), "no save left open: m-near committed, never rolled back");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "nothing in doubt");
        JournalWorld.Ref ref = JournalWorld.workRef(db.pool(), "m-near");
        assertNotNull(ref, "m-near's work row");
        assertEquals("node-a", ref.node(), "in node-a's journal");
        assertEquals(own, a.record(ref), "its record holds the body");
        Future<Buffer> read = opened(a.blobs, near.body().key());
        assertTrue(read.succeeded(), "m-near's body reads back: " + read.cause());
        assertEquals(own, read.result());
    }
}
