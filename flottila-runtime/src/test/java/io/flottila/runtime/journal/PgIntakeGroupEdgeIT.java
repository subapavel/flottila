package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6b (implementer): the group commit's edges the packet's cases leave open — keyed members of two flows in one group, a
 * lost or refused group COMMIT with a duplicate or another store's bodies among its members, a group whose connection cannot be
 * had, and a BEGIN that fails. Staged as {@link PgIntakeGroupIT} stages its groups: a plug whose COMMIT the relay holds, members
 * queued behind it on a fake timer that never fires; every wait bounded, no sleeps.
 */
@Timeout(180)
class PgIntakeGroupEdgeIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static final String FLOW = JournalWorld.FLOW;
    static final NodeConfig.IntakeGroup ONE_SLOT = new NodeConfig.IntakeGroup(64, 1L << 20, Duration.ofMillis(50), 1);

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool counted;
    JournalWorld a;
    FakeClock clock;
    FakeTimer timer;
    PgIntake intake;
    final List<AutoCloseable> closing = new ArrayList<>();

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
        db = CaseDatabase.create(pg, vertx, "intake_group_edge_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        closing.add(wire);
        counted = pool(wire.port(), 4);
        a = JournalWorld.open(vertx, counted, db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
        closing.add(a);
        clock = FakeClock.at(Instant.parse("2026-11-04T10:00:00Z"));
        timer = new FakeTimer(clock);
        intake = new PgIntake(counted, a.blobs, a.messages, a.queue, timer, ONE_SLOT);
    }

    @AfterEach
    void closeAll() throws Exception {
        wire.close();
        for (AutoCloseable c : closing.reversed()) c.close();
        if (db != null) db.close();
    }

    Pool pool(int port, int size) {
        Pool p = PgBuilder.pool()
                .connectingTo(Node.connectOptions(new NodeConfig.Pg("127.0.0.1", port, db.name(), pg.getUsername(), pg.getPassword(), size)))
                .with(new PoolOptions().setMaxSize(size)).using(vertx).build();
        closing.add(() -> p.close().await());
        return p;
    }

    void sql(String statement) {
        db.pool().query(statement).execute().await();
    }

    Future<String> call(PgIntake to, Envelope e, String flow, String key) throws Exception {
        return OnVirtualThread.join(OnVirtualThread.start(() -> to.save(e, JournalWorld.ingress(e), flow, "K-1", key, true, undecided())));
    }

    Future<String> call(String id, String key) throws Exception {
        return call(intake, a.arrive(id, bytes(1_000, id.hashCode())), FLOW, key);
    }

    /** A save alone in the one slot, held at its COMMIT: members called now queue behind it. */
    record Plug(PgWire.Held commit, Future<String> saved) {
        void unplug() {
            commit.release();
            assertTrue(settled(saved).succeeded(), "the plug saved: " + saved.cause());
        }
    }

    Plug plug() throws Exception {
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> saved = call("m-plug", null);
        commit.awaitReached();
        return new Plug(commit, saved);
    }

    /** An arrival whose body is another store's (not this node's journal): its durable save is that store's persist. */
    static Envelope elsewhere(InMemoryBlobStore store, String id) {
        Body body = settled(store.write(BlobKey.body(id, 0), bytes(300, id.hashCode()), 1L << 20)).result();
        return Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
    }

    long rows(String table, String id) {
        String column = table.equals("body_refs") ? "message_id" : "id";
        return count(db.pool(), "select count(*) from " + table + " where " + column + " = $1", id);
    }

    void nothingOf(String id) {
        assertEquals(0, rows("messages", id), id + ": no ingress row");
        assertEquals(0, rows("work", id), id + ": no item");
        assertEquals(0, rows("body_refs", id), id + ": no kept ref");
    }

    List<String> keyRows(String flow, String key) {
        List<String> ids = new ArrayList<>();
        db.pool().preparedQuery("select message_id from processed_ids where scope = $1 and key_hash = sha256(convert_to($2::text, 'UTF8'))")
                .execute(Tuple.of(flow, key)).await().forEach(r -> ids.add(r.getString(0)));
        return ids;
    }

    static void failedWith(Future<String> save, String id, String text) {
        assertTrue(settled(save).failed(), id + " fails: " + save);
        assertTrue(causes(save.cause()).stream().anyMatch(c -> c.contains(text)), id + " fails with '" + text + "': " + causes(save.cause()));
    }

    // --- cases

    /** One key in two flows in one group is two messages: each claims its own scope's row and saves; the claims are sorted by flow first. */
    @Test
    void theSameKeyInTwoFlowsInOneGroupIsTwoMessages() throws Exception {
        Plug plug = plug();
        Future<String> orders = call(intake, a.arrive("m-o", bytes(500, 1)), "shop/orders", "K-same");
        Future<String> invoices = call(intake, a.arrive("m-i", bytes(500, 2)), "shop/invoices", "K-same");
        Future<String> second = call(intake, a.arrive("m-o2", bytes(500, 3)), "shop/orders", "K-same");
        plug.unplug();

        assertEquals("m-o", settled(orders).result(), "the orders arrival claims the key in its flow: " + orders.cause());
        assertEquals("m-i", settled(invoices).result(), "the invoices arrival claims it in its own: " + invoices.cause());
        assertEquals("m-o", settled(second).result(), "a second orders arrival is the first's duplicate: " + second.cause());
        assertEquals(List.of("m-o"), keyRows("shop/orders", "K-same"));
        assertEquals(List.of("m-i"), keyRows("shop/invoices", "K-same"));
        nothingOf("m-o2");
        assertEquals(0, intake.groupStats().reruns(), "one group, committed");
    }

    /**
     * A group COMMIT whose reply is lost fails its duplicate too, without leaving it open: only the member writing rows stays open
     * until its transaction's status is known; the duplicate's body stays local (it was not answered).
     */
    @Test
    void aLostGroupCommitFailsItsDuplicateWithoutLeavingItOpen() throws Exception {
        assertEquals("m-first", settled(call("m-first", "K-dup")).result());
        Plug plug = plug();
        Future<String> writing = call("m-x", null);
        Future<String> duplicate = call("m-dup", "K-dup");
        PgWire.Held groupCommit = wire.holdNext(PgWire.query("COMMIT"));
        plug.unplug();
        groupCommit.awaitReached();
        groupCommit.cut();

        assertTrue(settled(writing).failed(), "the member writing rows fails: " + writing);
        assertTrue(settled(duplicate).failed(), "and its duplicate: every member of a lost COMMIT fails: " + duplicate);
        assertEquals(1, List.copyOf(intake.openSaves()).size(), "only the member writing rows is open: " + intake.openSaves());
        assertTrue(a.blobs.location(BlobKey.body("m-dup", 0)).isPresent(), "the duplicate's body is still local");

        groupCommit.abort();
        SegmentSweepIT.until(() -> count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() "
                + "and state like 'idle in transaction%'") == 0, () -> "the lost COMMIT's session gone");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "its outcome known: none open");
        nothingOf("m-x");
        nothingOf("m-dup");
        assertEquals(List.of("m-first"), keyRows(FLOW, "K-dup"));
    }

    /**
     * A refused group COMMIT whose members' bodies are another store's — no record of this node, so no transaction id is known —
     * fails every member with the commit's own refusal, never as a commit in doubt, and leaves none open.
     */
    @Test
    void aRefusedGroupCommitOfAnotherStoresBodiesFailsWithItsOwnCause() throws Exception {
        InMemoryBlobStore store = new InMemoryBlobStore(Hasher.sha256(), 1L << 20);
        sql("create function refuse_at_commit() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-e2' then raise exception 'commit refused by the test'; end if; return null; end $$");
        sql("create constraint trigger refuse_at_commit after insert on work deferrable initially deferred for each row execute function refuse_at_commit()");
        Plug plug = plug();
        Future<String> e1 = call(intake, elsewhere(store, "m-e1"), FLOW, null);
        Future<String> e2 = call(intake, elsewhere(store, "m-e2"), FLOW, null);
        plug.unplug();

        for (Future<String> save : List.of(e1, e2)) {
            failedWith(save, "a member", "commit refused by the test");
            assertTrue(causes(save.cause()).stream().noneMatch(c -> c.contains("reply lost")), "not a commit in doubt: " + causes(save.cause()));
        }
        nothingOf("m-e1");
        nothingOf("m-e2");
        assertEquals(List.of(), List.copyOf(intake.openSaves()));
    }

    /**
     * Review r1: a group COMMIT whose reply is lost when no member names a record of this node (another store's bodies: no
     * transaction id known) fails every member with the loss itself and leaves none open — nothing here to settle by.
     */
    @Test
    void aLostGroupCommitOfAnotherStoresBodiesFailsWithTheLossItself() throws Exception {
        InMemoryBlobStore store = new InMemoryBlobStore(Hasher.sha256(), 1L << 20);
        Plug plug = plug();
        Future<String> e1 = call(intake, elsewhere(store, "m-l1"), FLOW, null);
        Future<String> e2 = call(intake, elsewhere(store, "m-l2"), FLOW, null);
        PgWire.Held groupCommit = wire.holdNext(PgWire.query("COMMIT"));
        plug.unplug();
        groupCommit.awaitReached();
        groupCommit.cut();

        for (Future<String> save : List.of(e1, e2)) {
            assertTrue(settled(save).failed(), "its COMMIT's reply lost, it fails: " + save);
            assertTrue(causes(save.cause()).stream().noneMatch(c -> c.contains("pg_xact_status")), "not in doubt: no id to ask by: " + causes(save.cause()));
            assertTrue(save.cause().getMessage().contains("connection may have been lost"), "failed with the loss itself: " + causes(save.cause()));
        }
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "none open");
        groupCommit.abort();
    }

    /**
     * A lost group COMMIT with a member of this node's journal and one of another store keeps the first open by the group's
     * transaction id; once that COMMIT is known to have landed both are saved.
     */
    @Test
    void aLostGroupCommitWithAnotherStoresBodySettlesBothOnceKnown() throws Exception {
        InMemoryBlobStore store = new InMemoryBlobStore(Hasher.sha256(), 1L << 20);
        Plug plug = plug();
        Future<String> journaled = call("m-j", null);
        Future<String> other = call(intake, elsewhere(store, "m-e"), FLOW, null);
        PgWire.Held groupCommit = wire.holdNext(PgWire.query("COMMIT"));
        plug.unplug();
        groupCommit.awaitReached();
        groupCommit.cut();
        assertTrue(settled(journaled).failed() && settled(other).failed(), "both fail: their COMMIT's reply is lost");
        assertEquals(1, List.copyOf(intake.openSaves()).size(), "the journal's member open, the other store's has no open save");

        groupCommit.commitAfterCut();
        SegmentSweepIT.until(() -> rows("work", "m-j") == 1, () -> "the landed COMMIT visible");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "settled: none open");
        assertEquals(Optional.empty(), a.blobs.location(BlobKey.body("m-j", 0)), "m-j committed: its local copy gone");
        assertEquals(1, rows("work", "m-e"), "the other store's member's item committed with it");
    }

    /**
     * A group whose connection cannot be had runs each member again alone, and each fails with the connection's failure: nothing
     * written, every body local, none open. Staged on a pool whose server accepts one connection and never answers it: the first
     * save waits on it alone, the next two queue; then the server goes, refusing every connection.
     */
    @Test
    void aGroupWhoseConnectionFailsRunsEachMemberAloneAndEachFails() throws Exception {
        ServerSocket mute = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        closing.add(mute);
        CompletableFuture<Socket> accepted = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                accepted.complete(mute.accept());
            } catch (Exception e) {
                accepted.completeExceptionally(e);
            }
        });
        PgIntake dead = new PgIntake(pool(mute.getLocalPort(), 1), a.blobs, a.messages, a.queue, timer, ONE_SLOT);
        Future<String> first = call(dead, a.arrive("m-0", bytes(500, 10)), FLOW, null);
        Socket held = accepted.get(20, TimeUnit.SECONDS);
        Future<String> m1 = call(dead, a.arrive("m-1", bytes(500, 11)), FLOW, null);
        Future<String> m2 = call(dead, a.arrive("m-2", bytes(500, 12)), FLOW, null);
        assertFalse(first.isComplete() || m1.isComplete() || m2.isComplete(), "fixture: the first waits for its connection, two queue");

        mute.close();   // no more connections
        held.close();   // and the first one's goes

        for (Future<String> save : List.of(first, m1, m2)) assertTrue(settled(save).failed(), "no connection, no save: " + save);
        assertEquals(2, dead.groupStats().reruns(), "the group of two ran each member again alone");
        for (String id : List.of("m-0", "m-1", "m-2")) {
            nothingOf(id);
            assertTrue(a.blobs.location(BlobKey.body(id, 0)).isPresent(), id + ": its body still local");
        }
        assertEquals(List.of(), List.copyOf(dead.openSaves()), "none open");
    }

    /**
     * Review r1: a group whose every member is decided while it waits for its connection sends nothing on that connection — no
     * BEGIN — and its members fail as abandoned. Staged as PgIntakeGroupIT stages a connection wait: two slots on a cold pool of
     * two, the plug on its one connection, the second held at its startup.
     */
    @Test
    void aGroupWhollyDecidedWhileItWaitsForItsConnectionSendsNothing() throws Exception {
        PgIntake two = new PgIntake(counted, a.blobs, a.messages, a.queue, timer, new NodeConfig.IntakeGroup(64, 1L << 20, Duration.ofMillis(50), 2));
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> plugged = call(two, a.arrive("m-plug", bytes(500, 20)), FLOW, null);
        commit.awaitReached();
        io.vertx.core.Promise<Void> answer = io.vertx.core.Promise.promise();
        List<Future<String>> decided = new ArrayList<>();
        for (String id : List.of("m-d1", "m-d2")) {
            Envelope e = a.arrive(id, bytes(500, id.hashCode()));
            decided.add(OnVirtualThread.join(OnVirtualThread.start(() -> two.save(e, JournalWorld.ingress(e), FLOW, "K-1", null, true, answer.future()))));
        }
        PgWire.Held connecting = wire.holdNextConnection();
        int mark = wire.mark();
        clock.advance(Duration.ofMillis(50));   // the window takes the two: their group asks the pool for its second connection
        connecting.awaitReached();
        answer.complete();
        connecting.release();

        for (Future<String> save : decided) failedWith(save, "a decided member", "abandoned");
        // a save after them: its slot is the decided group's once that group is done, its connection that group's second one;
        // P2-16a (D-121 A3): under another key than the plug's, whose held COMMIT holds K-1's lock
        Envelope later = a.arrive("m-after", bytes(500, 21));
        Future<String> after = OnVirtualThread.join(OnVirtualThread.start(() -> two.save(later, JournalWorld.ingress(later), FLOW, "K-2", null, true, undecided())));
        SegmentSweepIT.until(() -> { clock.advance(Duration.ofMillis(50)); return after.isComplete(); }, () -> "the save after them is answered");
        assertTrue(after.succeeded(), "it saved: " + after.cause());
        List<String> sent = PgIntakeRoundTripIT.heads(wire.statementsSince(mark));
        assertEquals(1, sent.stream().filter("BEGIN"::equals).count(), "one BEGIN since the decided group was taken, the later save's: " + sent);
        assertEquals(0, sent.stream().filter("ROLLBACK"::equals).count(), "nothing rolled back: the decided group sent nothing: " + sent);
        commit.release();
        assertTrue(settled(plugged).succeeded(), "the plug saved: " + plugged.cause());
        nothingOf("m-d1");
        nothingOf("m-d2");
        assertEquals(List.of(), List.copyOf(two.openSaves()), "none open");
    }

    /** A BEGIN whose connection is lost fails the save alone, writing nothing; its body stays local and it is not left open. */
    @Test
    void aLostBeginFailsTheSaveAndWritesNothing() throws Exception {
        PgWire.Held begin = wire.holdNext(PgWire.query("BEGIN"));
        int mark = wire.mark();
        Future<String> save = call("m-b", null);
        begin.awaitReached();
        begin.cut();

        assertTrue(settled(save).failed(), "its BEGIN lost, the save fails: " + save);
        nothingOf("m-b");
        assertTrue(a.blobs.location(BlobKey.body("m-b", 0)).isPresent(), "its body still local");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "none open");
        // review r2 (3), test-author: a failing group of one fails as itself, never re-run
        assertEquals(0, intake.groupStats().reruns(), "a group of one is not re-run");
        assertEquals(1, PgIntakeRoundTripIT.heads(wire.statementsSince(mark)).stream().filter("BEGIN"::equals).count(),
                "one BEGIN, its own: " + wire.since(mark));
    }
}
