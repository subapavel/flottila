package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.batch.OverloadedException;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6b (D-109): the intake's group commit — saves waiting while a group is in flight share one transaction on one connection,
 * in 6a's turns (BEGIN awaited, the keyed members' claims and first-id reads in one turn, the rows in the next, the abandoned check,
 * COMMIT alone). A duplicate writes nothing and its neighbours commit; one key twice is one message; claims are sorted, so two
 * groups claiming two keys in opposite call order never deadlock; a failed group answers nothing from its transaction and re-runs
 * each surviving member alone; a refused COMMIT fails every member; a lost COMMIT keeps every member open until its xid's
 * {@code pg_xact_status} is known; a decided unkeyed member is dropped at take and after the connection (nothing sent for it), a
 * member decided before the check rolls the group back; a full backlog fails Overloaded.
 *
 * <p>Staging, without sleeps: a group forms behind a <b>plug</b> — a save alone in the one commit slot whose COMMIT {@link PgWire}
 * holds; members called meanwhile queue (the window never fires: the timer is a {@link FakeTimer} the case advances itself) and go
 * together, as one group, once the plug's COMMIT is released. A save is called on a virtual thread of its own and the call is
 * awaited: the ingress body's record is forced inline on that thread ({@code TieredBlobStore.forced}) and the member is queued
 * before the call returns — the members' call order is the order of the calls. Every wait is bounded.
 *
 * <p>The API assumed (CORE-6b packet, test-author): {@code record NodeConfig.IntakeGroup(int max, long bytes, Duration window, int
 * concurrency)}; {@code new PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue, io.flottila.api.Timer, IntakeGroup)};
 * {@code PgIntake.groupStats()} answering a record with {@code groups()}, {@code members()}, {@code bytes()}, {@code reruns()} —
 * totals since the intake was built, a group of one counted as a group, {@code reruns} the members re-run alone after their
 * group failed. A full backlog's {@link OverloadedException} names the capacity {@code max × concurrency × 16}.
 */
@Timeout(180)
class PgIntakeGroupIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;

    static final String FLOW = JournalWorld.FLOW;
    /** A window the fake clock never reaches unless a case advances it: members go when a slot frees. */
    static final Duration WINDOW = Duration.ofMillis(50);
    static final long MIB = 1L << 20;

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
    void database() throws Exception {
        db = CaseDatabase.create(pg, vertx, "intake_group_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        closing.add(wire);
        wire.countTurns();
    }

    /** The relay first: every hold a failed case left is released and every relayed connection closed, so nothing waits on a reply. */
    @AfterEach
    void closeAll() throws Exception {
        wire.close();
        for (AutoCloseable c : closing.reversed()) c.close();
        if (db != null) db.close();
    }

    // --- the world

    static NodeConfig.IntakeGroup group(int max, int concurrency) {
        return new NodeConfig.IntakeGroup(max, MIB, WINDOW, concurrency);
    }

    /**
     * Node-a over the relay: a pool of {@code poolSize} connections on the node's connect options (one open before any case
     * statement), the journal's rows on the case pool, and the intake grouping as {@code group} on the fake timer.
     */
    void open(NodeConfig.IntakeGroup group, int poolSize) throws Exception {
        open(group, poolSize, true);
    }

    /**
     * As {@link #open(NodeConfig.IntakeGroup, int)}, the one connection opened first only when {@code warm}. Implementer (CORE-6b,
     * fixture fix): a Vert.x 5.1 pool hands a caller an idle connection only on the caller's event loop while it may still open one
     * (javap {@code SimpleConnectionPool$Acquire}: same-loop selector, then a new connection below max, then any idle one), so a
     * case that needs "the plug is the pool's only connection" must not open one first.
     */
    void open(NodeConfig.IntakeGroup group, int poolSize, boolean warm) throws Exception {
        counted = PgBuilder.pool()
                .connectingTo(Node.connectOptions(new NodeConfig.Pg("127.0.0.1", wire.port(), db.name(), pg.getUsername(), pg.getPassword(), poolSize)))
                .with(new PoolOptions().setMaxSize(poolSize)).using(vertx).build();
        closing.add(() -> counted.close().await());
        a = JournalWorld.open(vertx, counted, db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
        closing.add(a);
        clock = FakeClock.at(Instant.parse("2026-11-04T10:00:00Z"));
        timer = new FakeTimer(clock);
        intake = new PgIntake(counted, a.blobs, a.messages, a.queue, timer, group);
        if (warm) counted.query("select 1").execute().await();
    }

    void sql(String statement) {
        db.pool().query(statement).execute().await();
    }

    Envelope arrive(String id) {
        return a.arrive(id, bytes(1_000, id.hashCode()));
    }

    /** {@code e}'s save — flow {@link #FLOW}, its input kept — called on a virtual thread of its own; returns once the call returned. */
    Future<String> call(Envelope e, String key, String partition, Future<?> abandoned) throws Exception {
        return OnVirtualThread.join(OnVirtualThread.start(() -> intake.save(e, JournalWorld.ingress(e), FLOW, partition, key, true, abandoned)));
    }

    Future<String> call(String id, String key, Future<?> abandoned) throws Exception {
        return call(arrive(id), key, "K-1", abandoned);
    }

    /** A save alone in a commit slot, held at its COMMIT: members called now queue behind it. */
    record Plug(String id, PgWire.Held commit, Future<String> saved) {}

    /** The writer idle: the save goes at once, alone; returns once its COMMIT is held at the relay. */
    Plug plug(String id) throws Exception {
        return plug(id, "K-1");
    }

    /**
     * {@link #plug(String)} under partition key {@code partition}. P2-16a (D-121 A3): a held COMMIT holds the partition key's lock
     * too, so a later group of another key's saves is not held behind it by the lock.
     */
    Plug plug(String id, String partition) throws Exception {
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> saved = call(arrive(id), null, partition, undecided());
        commit.awaitReached();
        return new Plug(id, commit, saved);
    }

    /**
     * A second slot's plug (concurrency 2, one group in flight) under partition key {@code partition}: queued, it goes when the case
     * advances the clock by the window. P2-16a: under another key than the first plug's, whose held COMMIT holds that key's lock.
     */
    Plug plugByWindow(String id, String partition) throws Exception {
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> saved = call(arrive(id), null, partition, undecided());
        clock.advance(WINDOW);
        commit.awaitReached();
        return new Plug(id, commit, saved);
    }

    /** Releases the plug's COMMIT and waits for its save: the members queued behind it go. */
    void unplug(Plug p) {
        p.commit().release();
        saved(p.saved(), p.id());
    }

    static void saved(Future<String> save, String id) {
        assertTrue(settled(save).succeeded(), id + " saved: " + save.cause());
        assertEquals(id, save.result(), "the save answers " + id);
    }

    static void failedWith(Future<String> save, String id, String text) {
        assertTrue(settled(save).failed(), id + " fails: " + save);
        assertTrue(causes(save.cause()).stream().anyMatch(c -> c.contains(text)), id + " fails with '" + text + "': " + causes(save.cause()));
    }

    // --- reading Postgres and the store

    /** Every insert into messages, work and body_refs from now on is logged with its message id and its transaction, in insert order. */
    void logTransactions() {
        sql("create table txlog(n bigserial primary key, tbl text not null, id text not null, txid bigint not null)");
        sql("create function log_tx() returns trigger language plpgsql as $$ begin insert into txlog(tbl, id, txid) values (tg_table_name, "
                + "coalesce(to_jsonb(new)->>'message_id', to_jsonb(new)->>'id'), txid_current()); return null; end $$");
        for (String t : List.of("messages", "work", "body_refs")) sql("create trigger log_tx after insert on " + t + " for each row execute function log_tx()");
    }

    /** The one transaction every logged row of {@code id} was inserted in; fails when there are none or several. */
    long txOf(String id) {
        TreeSet<Long> txids = new TreeSet<>();
        int rows = 0;
        for (Row r : db.pool().preparedQuery("select txid from txlog where id = $1").execute(Tuple.of(id)).await()) {
            txids.add(r.getLong(0));
            rows++;
        }
        assertEquals(3, rows, id + ": its record, its item and its kept ref were inserted: " + txids);
        assertEquals(1, txids.size(), id + ": all in one transaction: " + txids);
        return txids.first();
    }

    long rows(String table, String id) {
        String column = table.equals("body_refs") || table.equals("message_keys") ? "message_id" : "id";
        return count(db.pool(), "select count(*) from " + table + " where " + column + " = $1", id);
    }

    void savedWhole(String id) {
        assertEquals(1, rows("messages", id), id + ": its ingress row");
        assertEquals(1, rows("work", id), id + ": its item");
        assertEquals(1, rows("body_refs", id), id + ": its kept ref");
        assertEquals(Optional.empty(), a.blobs.location(BlobKey.body(id, 0)), id + ": committed, its local copy gone");
    }

    void nothingOf(String id) {
        assertEquals(0, rows("messages", id), id + ": no ingress row");
        assertEquals(0, rows("message_keys", id), id + ": no key row");
        assertEquals(0, rows("work", id), id + ": no item");
        assertEquals(0, rows("body_refs", id), id + ": no kept ref");
    }

    /** {@code id}'s body is local and reads as it arrived: nothing released it. */
    void stillLocal(String id) {
        BlobKey key = BlobKey.body(id, 0);
        assertTrue(a.blobs.location(key).isPresent(), id + ": its body is still local");
        Future<Buffer> read = JournalWorld.opened(a.blobs, key);
        assertTrue(read.succeeded(), id + ": and readable: " + read.cause());
        assertEquals(bytes(1_000, id.hashCode()), read.result(), id + ": as it arrived");
    }

    /** The message ids the key's processed_ids rows name. */
    List<String> keyRows(String key) {
        List<String> ids = new ArrayList<>();
        db.pool().preparedQuery("select message_id from processed_ids where scope = $1 and key_hash = sha256(convert_to($2::text, 'UTF8'))")
                .execute(Tuple.of(FLOW, key)).await().forEach(r -> ids.add(r.getString(0)));
        return ids;
    }

    /** The work rows' ids of partition {@code partition}, in seq order. */
    List<String> workIn(String partition) {
        List<String> ids = new ArrayList<>();
        db.pool().preparedQuery("select id from work where partition_key = $1 order by seq").execute(Tuple.of(partition)).await().forEach(r -> ids.add(r.getString(0)));
        return ids;
    }

    /** Advances the fake clock by one window: a window armed fires, taking what a free slot permits. */
    boolean windowPassed() {
        clock.advance(WINDOW);
        return true;
    }

    long lockWaiters() {
        return count(db.pool(), "select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'", db.name());
    }

    /** The statements sent after {@code mark} whose {@link PgIntakeRoundTripIT#head} is {@code head}. */
    long sent(int mark, String head) {
        return PgIntakeRoundTripIT.heads(wire.statementsSince(mark)).stream().filter(head::equals).count();
    }

    // --- cases

    /**
     * Eight saves queued while a group is in flight (concurrency 1) go as one group: one BEGIN and one COMMIT for the eight, their
     * rows in one transaction — BEGIN awaited, then the key's lock once for the eight (P2-16a, D-121 A3: all under K-1), the
     * messages and message_keys unnests and each member's item and kept ref in one turn, then COMMIT: 5 + 2·8 statements in
     * three waits; each member answers its own id and is committed.
     */
    @Test
    void concurrentSavesShareOneTransaction() throws Exception {
        open(group(64, 1), 1);
        logTransactions();
        Plug plug = plug("m-plug");   // a group of one; on the one connection it also prepares every statement of a group
        Map<String, Future<String>> members = new LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) members.put("m-" + i, call("m-" + i, null, undecided()));
        assertTrue(members.values().stream().noneMatch(Future::isComplete), "fixture: the eight wait behind the plug");

        int mark = wire.mark();
        unplug(plug);
        members.forEach((id, save) -> saved(save, id));

        List<List<String>> turns = wire.turnsSince(mark).stream().map(PgIntakeRoundTripIT::heads).toList();
        assertEquals(21, PgIntakeRoundTripIT.heads(wire.statementsSince(mark)).size(), "5 + 2*8 statements for the group of eight: " + wire.since(mark));
        assertEquals(3, turns.size(), "three waits — BEGIN, the rows, COMMIT: " + turns);
        assertEquals(List.of("BEGIN"), turns.get(0), "BEGIN awaited alone: " + turns);
        assertEquals(List.of(PgIntakeRoundTripIT.KEY_LOCK, "insert into messages", "insert into message_keys"), turns.get(1).subList(0, 3),
                "the key's lock first in the rows turn, then the records as one unnest each: " + turns);
        assertEquals(1, turns.get(1).stream().filter(PgIntakeRoundTripIT.KEY_LOCK::equals).count(), "one lock statement for the group's one key: " + turns);
        assertEquals(8, turns.get(1).stream().filter("insert into work"::equals).count(), "an item per member in the rows turn: " + turns);
        assertEquals(8, turns.get(1).stream().filter("insert into body_refs"::equals).count(), "a kept ref per member in the rows turn: " + turns);
        assertEquals(19, turns.get(1).size(), "nothing else in the rows turn: " + turns);
        assertEquals(List.of("COMMIT"), turns.get(2), "COMMIT alone: " + turns);

        long tx = txOf("m-1");
        for (String id : members.keySet()) {
            assertEquals(tx, txOf(id), id + " shares the group's transaction");
            savedWhole(id);
        }
        assertNotEquals(txOf("m-plug"), tx, "the plug committed in its own");
        var stats = intake.groupStats();
        assertEquals(2, stats.groups(), "two groups: the plug's and the eight's");
        assertEquals(9, stats.members(), "nine members");
        assertEquals(0, stats.reruns(), "no member re-run");
        long bound = 0;
        for (String id : concat(List.of("m-plug"), members.keySet())) bound += (id + FLOW).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(stats.bytes() >= bound, "a member weighs at least its id and flow bytes: " + stats.bytes() + " < " + bound);
    }

    /**
     * A duplicate in a group writes nothing — no savepoint, no rollback — answers the first message's id once the group commits,
     * its body evicted; its neighbours commit, in the group's one transaction, unaffected.
     */
    @Test
    void aDuplicateInAGroupAnswersTheFirstIdAndItsNeighboursCommit() throws Exception {
        open(group(64, 1), 2);
        saved(call("m-first", "K-dup", undecided()), "m-first");
        logTransactions();
        Plug plug = plug("m-plug");
        Future<String> before = call("m-a", null, undecided());
        Future<String> duplicate = call("m-dup", "K-dup", undecided());
        Future<String> after = call("m-b", "K-new", undecided());

        int mark = wire.mark();
        unplug(plug);
        saved(before, "m-a");
        assertTrue(settled(duplicate).succeeded(), "a duplicate in a group is a save that succeeds: " + duplicate.cause());
        assertEquals("m-first", duplicate.result(), "answering the first message's id");
        saved(after, "m-b");

        savedWhole("m-a");
        savedWhole("m-b");
        nothingOf("m-dup");
        assertEquals(Optional.empty(), a.blobs.location(BlobKey.body("m-dup", 0)), "the duplicate's local body is evicted");
        assertEquals(txOf("m-a"), txOf("m-b"), "the neighbours committed in the group's one transaction");
        assertEquals(List.of("m-first"), keyRows("K-dup"), "the key still names the first");
        assertEquals(List.of("m-b"), keyRows("K-new"), "the neighbour's key names it");
        assertEquals(1, sent(mark, "BEGIN"), "one transaction: " + wire.since(mark));
        assertEquals(1, sent(mark, "COMMIT"), "committed once: " + wire.since(mark));
        assertEquals(0, sent(mark, "ROLLBACK"), "nothing rolled back: " + wire.since(mark));
        assertTrue(wire.statementsSince(mark).stream().noneMatch(s -> s.toLowerCase(Locale.ROOT).contains("savepoint")),
                "no savepoint: the duplicate's claim inserts nothing (on conflict do nothing): " + wire.since(mark));
        assertEquals(0, intake.groupStats().reruns(), "no member re-run");
    }

    /**
     * One key twice in a group is one message: the first in call order claims the key and saves, the second answers the first's
     * id — only once the group's COMMIT is acknowledged — and writes nothing.
     */
    @Test
    void oneKeyTwiceInAGroupIsOneMessage() throws Exception {
        open(group(64, 1), 2);
        Plug plug = plug("m-plug");
        int mark = wire.mark();
        Future<String> first = call("m-2", "K-twice", undecided());   // called first, though its id sorts after the second's
        Future<String> second = call("m-1", "K-twice", undecided());
        PgWire.Held groupCommit = wire.holdNext(PgWire.query("COMMIT"));

        unplug(plug);
        groupCommit.awaitReached();
        assertFalse(first.isComplete(), "the claimant is not answered before its COMMIT is acknowledged");
        assertFalse(second.isComplete(), "nor the second: it answers the first's id only after the commit");
        groupCommit.release();

        saved(first, "m-2");
        assertTrue(settled(second).succeeded(), "the second is a duplicate, a save that succeeds: " + second.cause());
        assertEquals("m-2", second.result(), "answering the id of the first in call order");
        savedWhole("m-2");
        nothingOf("m-1");
        assertEquals(Optional.empty(), a.blobs.location(BlobKey.body("m-1", 0)), "the second's local body is evicted");
        assertEquals(List.of("m-2"), keyRows("K-twice"), "one key row, naming the first in call order");
        assertEquals(List.of("m-plug", "m-2"), workIn("K-1"), "one item for the key's two arrivals");
        assertEquals(1, sent(mark, "BEGIN"), "both in one group's transaction: " + wire.since(mark));
        assertEquals(0, sent(mark, "ROLLBACK"), "which commits: the second wrote nothing to roll back: " + wire.since(mark));
    }

    /**
     * Two groups open at once, claiming the same two keys in opposite call order, both save: claims go sorted by (flow, key), one
     * lock order, so the second waits for the first instead of deadlocking (40P01), then answers its ids. Staged by a BEFORE INSERT
     * trigger on processed_ids that holds a transaction's second claimed row on an advisory lock the case holds: the first group
     * holds one key and waits there; the second group's claim is then sent and waits too, on a row lock (sorted) or at its own second
     * row (unsorted — a deadlock once the advisory lock goes).
     */
    @Test
    void groupsClaimingTwoKeysInOppositeOrderBothSave() throws Exception {
        open(group(64, 2), 4);
        logTransactions();
        sql("create function claim_pause() returns trigger language plpgsql as $$ declare n int := coalesce(nullif(current_setting('test.claims', true), ''), '0')::int + 1; "
                + "begin perform set_config('test.claims', n::text, true); if n = 2 then perform pg_advisory_xact_lock_shared(4242); end if; return new; end $$");
        sql("create trigger claim_pause before insert on processed_ids for each row execute function claim_pause()");
        SqlConnection gate = db.pool().getConnection().await();
        closing.add(() -> gate.close().await());
        gate.query("select pg_advisory_lock(4242)").execute().await();

        Plug first = plug("m-plug-1");
        Plug second = plugByWindow("m-plug-2", "K-plug-2");   // P2-16a: not behind the first plug's K-1 lock — the case is about claims
        Future<String> a1 = call("m-a1", "K-1", undecided());
        Future<String> a2 = call("m-a2", "K-2", undecided());
        unplug(first);
        // the window sends group A once the plug's slot is free; advanced until then (its answer may come before the slot frees)
        SegmentSweepIT.until(() -> windowPassed() && lockWaiters() == 1, () -> "group A holds K-1 and waits at its second claimed row");
        Future<String> b1 = call("m-b1", "K-2", undecided());
        Future<String> b2 = call("m-b2", "K-1", undecided());
        unplug(second);
        SegmentSweepIT.until(() -> windowPassed() && lockWaiters() == 2, () -> "group B goes while A is open, and its claim waits as well");
        gate.query("select pg_advisory_unlock(4242)").execute().await();

        saved(a1, "m-a1");
        saved(a2, "m-a2");
        assertTrue(settled(b1).succeeded(), "group B saves too, never a deadlock: " + b1.cause());
        assertTrue(settled(b2).succeeded(), "group B saves too, never a deadlock: " + b2.cause());
        assertEquals("m-a2", b1.result(), "K-2 was A's second's");
        assertEquals("m-a1", b2.result(), "K-1 was A's first's");
        assertEquals(0, intake.groupStats().reruns(), "no group failed and re-ran its members: no 40P01");
        assertEquals(txOf("m-a1"), txOf("m-a2"), "group A committed as one transaction");
        nothingOf("m-b1");
        nothingOf("m-b2");
    }

    /**
     * A poison member — its item refused — fails alone: the group rolls back, answers nothing from its transaction, and every
     * member re-runs alone in its own; the poison fails with its own refusal, the others save, each in a transaction of its own.
     */
    @Test
    void aPoisonMemberFailsAloneAndTheOthersSaveInTheirOwnTransactions() throws Exception {
        open(group(64, 1), 2);
        logTransactions();
        sql("create function refuse_poison() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-poison' then raise exception 'poison refused by the test'; end if; return new; end $$");
        sql("create trigger refuse_poison before insert on work for each row execute function refuse_poison()");
        Plug plug = plug("m-plug");
        Future<String> before = call("m-1", null, undecided());
        Future<String> poison = call("m-poison", null, undecided());
        Future<String> after = call("m-3", null, undecided());

        int mark = wire.mark();
        unplug(plug);
        saved(before, "m-1");
        failedWith(poison, "m-poison", "poison refused by the test");
        saved(after, "m-3");

        savedWhole("m-1");
        savedWhole("m-3");
        nothingOf("m-poison");
        stillLocal("m-poison");
        assertNotEquals(txOf("m-1"), txOf("m-3"), "each neighbour committed in a transaction of its own");
        assertEquals(3, intake.groupStats().reruns(), "every member of the failed group re-ran alone");
        assertEquals(4, sent(mark, "BEGIN"), "the group, then three transactions of one: " + wire.since(mark));
        assertEquals(2, sent(mark, "COMMIT"), "only the neighbours' own transactions commit: " + wire.since(mark));
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "no save left open");
    }

    /** A refused group COMMIT fails every member — none re-run — with nothing of any committed and every body still local. */
    @Test
    void aRefusedGroupCommitFailsEveryMemberAndLeavesTheirBodiesLocal() throws Exception {
        open(group(64, 1), 2);
        sql("create function refuse_at_commit() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-2' then raise exception 'commit refused by the test'; end if; return null; end $$");
        sql("create constraint trigger refuse_at_commit after insert on work deferrable initially deferred for each row execute function refuse_at_commit()");
        Plug plug = plug("m-plug");
        Map<String, Future<String>> members = new LinkedHashMap<>();
        for (String id : List.of("m-1", "m-2", "m-3")) members.put(id, call(id, null, undecided()));

        int mark = wire.mark();
        unplug(plug);
        members.forEach((id, save) -> failedWith(save, id, "commit refused by the test"));
        for (String id : members.keySet()) {
            nothingOf(id);
            stillLocal(id);
        }
        assertEquals(0, intake.groupStats().reruns(), "a refused COMMIT re-runs nobody");
        assertEquals(1, sent(mark, "BEGIN"), "one transaction: " + wire.since(mark));
        assertEquals(1, sent(mark, "COMMIT"), "its one COMMIT: " + wire.since(mark));
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "every member rolled back: none left open");
    }

    /**
     * Review r1 (d): a COMMIT refused by an error reply is no doubt — the server answered, the transaction aborted: every member
     * fails with that refusal itself (never as a commit in doubt) and is out of the open saves at once, no {@code pg_xact_status}
     * asked ({@code openSaves} of the store, which settles nothing, is empty before the intake's is ever called).
     */
    @Test
    void aRefusedGroupCommitAnswersItsOwnCauseAndLeavesNothingOpen() throws Exception {
        open(group(64, 1), 2);
        sql("create function refuse_at_commit() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-2' then raise exception 'commit refused by the test'; end if; return null; end $$");
        sql("create constraint trigger refuse_at_commit after insert on work deferrable initially deferred for each row execute function refuse_at_commit()");
        Plug plug = plug("m-plug");
        Map<String, Future<String>> members = new LinkedHashMap<>();
        for (String id : List.of("m-1", "m-2")) members.put(id, call(id, null, undecided()));

        unplug(plug);
        members.forEach((id, save) -> {
            failedWith(save, id, "commit refused by the test");
            assertTrue(save.cause().getMessage().contains("commit refused by the test"), id + " fails with the refusal itself: " + causes(save.cause()));
            assertTrue(causes(save.cause()).stream().noneMatch(c -> c.contains("pg_xact_status")), id + ": never as a commit in doubt: " + causes(save.cause()));
        });
        assertEquals(List.of(), List.copyOf(a.blobs.openSaves()), "a known abort: every member rolled back at once, nothing left to settle");
        for (String id : members.keySet()) {
            nothingOf(id);
            stillLocal(id);
        }
    }

    /**
     * Review r2 (1): a FATAL error in place of the COMMIT's reply is no known abort — the server committed (its CommandComplete
     * swallowed by the relay), then answered 57P01 and closed the connection: every member fails, yet stays open — the store's open
     * saves, which settle nothing, still hold them — until {@code pg_xact_status} is asked, which settles each committed.
     */
    @Test
    void aFatalErrorAfterTheGroupCommitKeepsEveryMemberOpenUntilItSettlesCommitted() throws Exception {
        open(group(64, 1), 2);
        Plug plug = plug("m-plug");
        Map<String, Future<String>> members = new LinkedHashMap<>();
        for (String id : List.of("m-1", "m-2")) members.put(id, call(id, null, undecided()));
        PgWire.FatalReply fatal = wire.fatalReplyTo(PgWire.query("COMMIT"));   // the plug's COMMIT is past: the next is the group's

        unplug(plug);
        fatal.awaitDone();
        members.forEach((id, save) -> failedWith(save, id, "57P01"));
        assertEquals(2, List.copyOf(a.blobs.openSaves()).size(), "a FATAL reply is no known abort: both members stay open, their outcome unknown");
        for (String id : members.keySet()) stillLocal(id);

        assertEquals(List.of(), List.copyOf(intake.openSaves()), "asked by pg_xact_status: committed, none left open");
        for (String id : members.keySet()) savedWhole(id);
    }

    /**
     * Review r1 (a): the survivors of a failed group re-run one after the other — with the first re-run's COMMIT held, the next
     * survivor's BEGIN is not sent; released, each saves in a transaction of its own and the poison fails alone.
     */
    @Test
    void theSurvivorsOfAFailedGroupReRunOneAfterTheOther() throws Exception {
        open(group(64, 1), 4);
        logTransactions();
        sql("create function refuse_poison() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-poison' then raise exception 'poison refused by the test'; end if; return new; end $$");
        sql("create trigger refuse_poison before insert on work for each row execute function refuse_poison()");
        Plug plug = plug("m-plug");
        Future<String> first = call("m-1", null, undecided());
        Future<String> second = call("m-2", null, undecided());
        Future<String> poison = call("m-poison", null, undecided());
        PgWire.Held firstRerunCommit = wire.holdNext(PgWire.query("COMMIT"));   // the group never commits: the next COMMIT is m-1's re-run

        int mark = wire.mark();
        unplug(plug);
        firstRerunCommit.awaitReached();
        wire.awaitQuiet();
        assertEquals(2, sent(mark, "BEGIN"), "the group's and the first re-run's only: the next waits for its COMMIT: " + wire.since(mark));
        assertFalse(second.isComplete(), "the second survivor is not run while the first re-run is open");
        firstRerunCommit.release();

        saved(first, "m-1");
        saved(second, "m-2");
        failedWith(poison, "m-poison", "poison refused by the test");
        assertNotEquals(txOf("m-1"), txOf("m-2"), "each in a transaction of its own");
        assertEquals(4, sent(mark, "BEGIN"), "the group, then three re-runs: " + wire.since(mark));
    }

    /**
     * Review r1 (b): a keyed duplicate whose answer is decided, in a group a poison fails, is re-run like any survivor — never
     * dropped as abandoned: alone it claims, finds its key taken, answers the first id and its body is evicted.
     */
    @Test
    void aDecidedKeyedDuplicateInAPoisonedGroupStillAnswersTheFirstId() throws Exception {
        open(group(64, 1), 2);
        sql("create function refuse_poison() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-poison' then raise exception 'poison refused by the test'; end if; return new; end $$");
        sql("create trigger refuse_poison before insert on work for each row execute function refuse_poison()");
        saved(call("m-first", "K-dup", undecided()), "m-first");
        Plug plug = plug("m-plug");
        Future<String> neighbour = call("m-a", null, undecided());
        Future<String> duplicate = call("m-dup", "K-dup", Future.succeededFuture());   // its answer decided already
        Future<String> poison = call("m-poison", null, undecided());

        unplug(plug);
        assertTrue(settled(duplicate).succeeded(), "the decided duplicate re-runs and answers, never abandoned: " + duplicate.cause());
        assertEquals("m-first", duplicate.result(), "the first id");
        assertEquals(Optional.empty(), a.blobs.location(BlobKey.body("m-dup", 0)), "its local body evicted");
        nothingOf("m-dup");
        saved(neighbour, "m-a");
        savedWhole("m-a");
        failedWith(poison, "m-poison", "poison refused by the test");
        assertEquals(List.of("m-first"), keyRows("K-dup"), "the key still names the first");
    }

    /**
     * A group COMMIT whose reply is lost fails every member and keeps each open — by the group's one xid — until its
     * {@code pg_xact_status} is known: a COMMIT that landed settles every member committed (local copies gone), one that never
     * reached the server every member rolled back (bodies local).
     */
    @Test
    void aLostGroupCommitKeepsEveryMemberOpenUntilItsXactStatusIsKnown() throws Exception {
        open(group(64, 1), 4);
        PgWire.Held landed = lostGroupCommit("m-plug-1", "K-1", List.of("m-x1", "m-x2"));
        // P2-16a: the landed group's transaction stays open, holding K-1's lock, until commitAfterCut — the second group under another key
        PgWire.Held lost = lostGroupCommit("m-plug-2", "K-2", List.of("m-y1", "m-y2"));
        assertEquals(4, List.copyOf(intake.openSaves()).size(), "every member of both groups open, their outcome unknown: " + intake.openSaves());
        for (String id : List.of("m-x1", "m-x2", "m-y1", "m-y2")) stillLocal(id);

        landed.commitAfterCut();
        lost.abort();
        SegmentSweepIT.until(() -> rows("work", "m-x1") == 1
                        && count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'") == 0,
                () -> "the landed COMMIT visible and the lost one's session gone");

        assertEquals(List.of(), List.copyOf(intake.openSaves()), "both outcomes known: no member left open");
        for (String id : List.of("m-x1", "m-x2")) savedWhole(id);
        for (String id : List.of("m-y1", "m-y2")) {
            nothingOf(id);
            stillLocal(id);
        }
    }

    /**
     * A group of {@code members} behind plug {@code plugId}, all under partition key {@code partition}, whose COMMIT reply is cut: each
     * member fails, its body local.
     */
    PgWire.Held lostGroupCommit(String plugId, String partition, List<String> members) throws Exception {
        Plug plug = plug(plugId, partition);
        List<Future<String>> saves = new ArrayList<>();
        for (String id : members) saves.add(call(arrive(id), null, partition, undecided()));
        PgWire.Held groupCommit = wire.holdNext(PgWire.query("COMMIT"));
        unplug(plug);
        groupCommit.awaitReached();
        groupCommit.cut();
        for (int i = 0; i < members.size(); i++) {
            assertTrue(settled(saves.get(i)).failed(), members.get(i) + ": its group's COMMIT reply lost, it fails: " + saves.get(i));
        }
        return groupCommit;
    }

    /**
     * A member whose answer is decided while it waits in the gather is dropped at the take: it fails abandoned, nothing is sent for
     * it, its body stays local; the rest commit as one group, no re-run.
     */
    @Test
    void aMemberAbandonedWhileGatheredWritesNothingAndTheRestCommit() throws Exception {
        open(group(64, 1), 2);
        logTransactions();
        Plug plug = plug("m-plug");
        Promise<Void> answer = Promise.promise();
        Future<String> before = call("m-1", null, undecided());
        Future<String> decided = call("m-2", null, answer.future());
        Future<String> after = call("m-3", null, undecided());
        answer.complete();   // the delivery's answer, while the member waits behind the plug

        int mark = wire.mark();
        unplug(plug);
        saved(before, "m-1");
        failedWith(decided, "m-2", "abandoned");
        saved(after, "m-3");

        nothingOf("m-2");
        stillLocal("m-2");
        assertEquals(txOf("m-1"), txOf("m-3"), "the rest committed as one group");
        assertEquals(2, sent(mark, "insert into work"), "nothing sent for the decided member: " + wire.since(mark));
        assertEquals(1, sent(mark, "BEGIN"), "one transaction: " + wire.since(mark));
        assertEquals(0, intake.groupStats().reruns(), "no member re-run");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "the dropped member rolled back: none left open");
    }

    /**
     * A group whose every member is decided at the take asks no connection: its members fail abandoned while the only connection is
     * still held by the plug's open transaction (concurrency 2 over a pool of one).
     */
    @Test
    void aGroupWhollyDecidedAtTakeFailsWithoutWaitingForAConnection() throws Exception {
        open(group(64, 2), 1);
        Plug plug = plug("m-plug");
        Promise<Void> answer = Promise.promise();
        Future<String> first = call("m-1", null, answer.future());
        Future<String> second = call("m-2", null, answer.future());
        answer.complete();
        int mark = wire.mark();
        clock.advance(WINDOW);   // a slot is free: the window takes the two

        failedWith(first, "m-1", "abandoned");   // with the plug's COMMIT still held: the pool's one connection is taken
        failedWith(second, "m-2", "abandoned");
        assertEquals(List.of(), wire.since(mark), "nothing sent for them");
        unplug(plug);
        nothingOf("m-1");
        nothingOf("m-2");
        stillLocal("m-1");
        stillLocal("m-2");
    }

    /**
     * A member decided while its group waits for a connection is dropped once the connection comes: nothing sent for it, the rest
     * commit as one group, no re-run. Staged by holding the pool's second connection at its startup ({@link PgWire#holdNextConnection}).
     */
    @Test
    void aMemberDecidedWhileItsGroupWaitsForAConnectionWritesNothing() throws Exception {
        open(group(64, 2), 2, false);
        logTransactions();
        Plug plug = plug("m-plug");   // on the pool's one open connection
        Promise<Void> answer = Promise.promise();
        // P2-16a: the plug's held COMMIT holds K-1's lock; the group under another key commits while the plug is held
        Future<String> before = call(arrive("m-1"), null, "K-2", undecided());
        Future<String> decided = call(arrive("m-2"), null, "K-2", answer.future());
        Future<String> after = call(arrive("m-3"), null, "K-2", undecided());
        PgWire.Held connecting = wire.holdNextConnection();
        int mark = wire.mark();
        clock.advance(WINDOW);   // the window takes the three: their group asks the pool, which opens its second connection
        connecting.awaitReached();
        answer.complete();
        connecting.release();

        saved(before, "m-1");
        failedWith(decided, "m-2", "abandoned");
        saved(after, "m-3");
        unplug(plug);
        nothingOf("m-2");
        stillLocal("m-2");
        assertEquals(txOf("m-1"), txOf("m-3"), "the rest committed as one group");
        assertEquals(2, sent(mark, "insert into work"), "nothing sent for the decided member: " + wire.since(mark));
        assertEquals(0, intake.groupStats().reruns(), "no member re-run");
    }

    /**
     * A member decided after its rows were sent and before the abandoned check rolls the group back: it fails abandoned, nothing
     * of it commits though its item was inserted; every other member re-runs alone and saves in a transaction of its own. Staged
     * by an advisory lock the third member's item insert waits on.
     */
    @Test
    void aMemberAbandonedAtTheCheckRollsTheGroupBackAndTheRestSaveAlone() throws Exception {
        open(group(64, 1), 2);
        logTransactions();
        sql("create sequence m2_items");
        sql("create function hold_m3() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-2' then perform nextval('m2_items'); end if; "
                + "if new.id = 'm-3' then perform pg_advisory_xact_lock_shared(4343); end if; return new; end $$");
        sql("create trigger hold_m3 before insert on work for each row execute function hold_m3()");
        SqlConnection gate = db.pool().getConnection().await();
        closing.add(() -> gate.close().await());
        gate.query("select pg_advisory_lock(4343)").execute().await();
        Plug plug = plug("m-plug");
        Promise<Void> answer = Promise.promise();
        Future<String> before = call("m-1", null, undecided());
        Future<String> decided = call("m-2", null, answer.future());
        Future<String> after = call("m-3", null, undecided());

        int mark = wire.mark();
        unplug(plug);
        SegmentSweepIT.until(() -> lockWaiters() == 1, () -> "the group waits at m-3's item, m-2's inserted");
        answer.complete();   // decided while the group's transaction is open
        gate.query("select pg_advisory_unlock(4343)").execute().await();

        saved(before, "m-1");
        failedWith(decided, "m-2", "abandoned");
        saved(after, "m-3");
        assertTrue(count(db.pool(), "select count(*) from m2_items where is_called") == 1, "fixture: m-2's item was inserted, then rolled back");
        nothingOf("m-2");
        stillLocal("m-2");
        savedWhole("m-1");
        savedWhole("m-3");
        assertNotEquals(txOf("m-1"), txOf("m-3"), "each of the rest re-ran in a transaction of its own");
        assertEquals(2, intake.groupStats().reruns(), "the two undecided members re-ran alone, the decided one did not");
        assertEquals(2, sent(mark, "COMMIT"), "the group never commits, the two re-runs do: " + wire.since(mark));
    }

    /**
     * A keyed member whose answer is decided before the take still claims: its key taken, it answers the first id, and — being
     * no live member — its decided answer does not roll the group back: its neighbour commits, no re-run.
     */
    @Test
    void aKeyedMemberWithItsAnswerDecidedStillAnswersItsDuplicate() throws Exception {
        open(group(64, 1), 2);
        saved(call("m-first", "K-dup", undecided()), "m-first");
        Plug plug = plug("m-plug");
        int mark = wire.mark();
        Future<String> neighbour = call("m-a", null, undecided());
        Future<String> duplicate = call("m-dup", "K-dup", Future.succeededFuture());   // its answer decided already

        unplug(plug);
        assertTrue(settled(duplicate).succeeded(), "a decided keyed member still claims and answers its duplicate: " + duplicate.cause());
        assertEquals("m-first", duplicate.result(), "the first id, never an abandoned save");
        saved(neighbour, "m-a");
        savedWhole("m-a");
        nothingOf("m-dup");
        assertEquals(0, intake.groupStats().reruns(), "the duplicate is no live member: the group committed");
        assertEquals(1, sent(mark, "BEGIN"), "both in one group's transaction: " + wire.since(mark));
        assertEquals(1, sent(mark, "COMMIT"), "which commits: " + wire.since(mark));
        assertEquals(0, sent(mark, "ROLLBACK"), "and is never rolled back for the decided duplicate: " + wire.since(mark));
    }

    /** Members of one partition keep their call order in the group's one transaction: the items' seq follows the calls. */
    @Test
    void sameKeyMembersKeepCallOrderInOneTransaction() throws Exception {
        open(group(64, 1), 2);
        logTransactions();
        Plug plug = plug("m-plug");
        List<String> order = List.of("m-d", "m-a", "m-c", "m-b");   // neither sorted nor reversed
        Map<String, Future<String>> members = new LinkedHashMap<>();
        for (String id : order) members.put(id, call(arrive(id), null, "P-1", undecided()));

        unplug(plug);
        members.forEach((id, save) -> saved(save, id));
        assertEquals(order, workIn("P-1"), "the partition's items in call order");
        long tx = txOf("m-d");
        for (String id : order) assertEquals(tx, txOf(id), id + " in the group's one transaction");
    }

    /**
     * A full backlog — max × concurrency × 16 members queued, here 2 × 1 × 16 — fails the next save Overloaded (retryable) at once:
     * it writes nothing, its body stays local and its save is not left open; the queued members all save once the slot frees.
     */
    @Test
    void aFullBacklogIsOverloaded() throws Exception {
        open(new NodeConfig.IntakeGroup(2, MIB, Duration.ZERO, 1), 2);
        Plug plug = plug("m-plug");
        Map<String, Future<String>> queued = new LinkedHashMap<>();
        for (int i = 0; i < 32; i++) queued.put("m-%02d".formatted(i), call("m-%02d".formatted(i), null, undecided()));
        Future<String> over = call("m-over", null, undecided());

        assertTrue(settled(over).failed(), "the 33rd save fails at once: " + over);
        OverloadedException e = assertInstanceOf(OverloadedException.class, over.cause(), "Overloaded: " + causes(over.cause()));
        assertEquals(32, e.capacity(), "the backlog's capacity: max 2 × concurrency 1 × 16");
        assertEquals(33, List.copyOf(intake.openSaves()).size(), "the plug and the 32 queued are open; the refused save is not");
        unplug(plug);
        queued.forEach((id, save) -> saved(save, id));
        nothingOf("m-over");
        stillLocal("m-over");
        assertEquals(List.of(), List.copyOf(intake.openSaves()));
    }

    /**
     * A group stops at its byte limit: a member weighs the bytes it binds, its headers among them, so members of 40 000 header bytes
     * under a 64 KiB limit go one per group, and small members join the last of them.
     */
    @Test
    void aGroupStopsAtItsByteLimit() throws Exception {
        open(new NodeConfig.IntakeGroup(64, 64 * 1024, WINDOW, 1), 2);
        logTransactions();
        Plug plug = plug("m-plug");
        Map<String, Future<String>> members = new LinkedHashMap<>();
        for (String id : List.of("m-p1", "m-p2", "m-p3")) {
            Envelope padded = Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("X-Pad", "x".repeat(40_000)),
                    arrive(id).body());
            members.put(id, call(padded, null, "K-1", undecided()));
        }
        for (String id : List.of("m-s1", "m-s2")) members.put(id, call(id, null, undecided()));

        unplug(plug);
        members.forEach((id, save) -> saved(save, id));
        var stats = intake.groupStats();
        assertEquals(4, stats.groups(), "the plug's, then [p1], [p2], [p3 s1 s2]: " + stats);
        assertEquals(6, stats.members(), "six members: " + stats);
        assertTrue(stats.bytes() >= 3 * 40_000L, "the headers' bytes are weighed: " + stats);
        assertNotEquals(txOf("m-p1"), txOf("m-p2"), "two padded members never share a group");
        assertNotEquals(txOf("m-p2"), txOf("m-p3"), "two padded members never share a group");
        assertEquals(txOf("m-p3"), txOf("m-s1"), "small members join the last padded one within the limit");
        assertEquals(txOf("m-p3"), txOf("m-s2"), "small members join the last padded one within the limit");
    }

    static <T> List<T> concat(java.util.Collection<T> first, java.util.Collection<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
