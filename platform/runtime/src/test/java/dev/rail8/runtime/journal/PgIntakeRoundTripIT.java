package dev.rail8.runtime.journal;

import dev.rail8.api.Envelope;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.testkit.SharedPostgres;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static dev.rail8.runtime.journal.JournalWorld.bytes;
import static dev.rail8.runtime.journal.JournalWorld.causes;
import static dev.rail8.runtime.journal.JournalWorld.count;
import static dev.rail8.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6a (D-109, F-26): the save's round trips on the node's connect options ({@link Node#connectOptions}: the prepared-statement
 * cache on), counted at the client's side of the wire ({@link PgWire}, its turns counted). On a connection that ran the save's
 * statements once, a save is BEGIN awaited alone; the rows (messages, message_keys, work) sent together; then COMMIT alone — 3
 * waits. A second save on the one connection sends no Parse. A refused row statement rolls the
 * save back: nothing of it visible, no COMMIT sent.
 *
 * <p>The journal's own rows go to the case pool: only the intake's statements cross the counted wire, on a pool of one
 * connection. Every wait is bounded (held replies, {@link PgWire#awaitQuiet}); no sleep.
 */
@Timeout(120)
class PgIntakeRoundTripIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;

    /** The head of the key-lock statement (D-121 A3): {@code select pg_advisory_xact_lock(..)} over the save's partition keys. */
    static final String KEY_LOCK = "select pg_advisory_xact_lock";
    /**
     * The rows turn of a save under partition key {@code K-1}: the key's lock first (P2-16a, D-121 A3: sent with the rows, no wait
     * of its own), then the record (messages, message_keys) and the item.
     */
    static final List<String> ROWS = List.of(KEY_LOCK, "insert into messages", "insert into message_keys", "insert into work");

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    JournalWorld a;
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

    /** The case database, the counting relay, a pool of one connection on the node's connect options over it, and node-a. */
    @BeforeEach
    void world() throws Exception {
        db = CaseDatabase.create(pg, vertx, "intake_trips_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        closing.add(wire);
        wire.countTurns();
        Pool counted = PgBuilder.pool()
                .connectingTo(Node.connectOptions(new NodeConfig.Pg("127.0.0.1", wire.port(), db.name(), pg.getUsername(), pg.getPassword(), 1)))
                .with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        closing.add(() -> counted.close().await());
        a = JournalWorld.open(vertx, counted, db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
        closing.add(a);
        counted.query("select 1").execute().await();   // the one connection is open before any count starts
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : closing.reversed()) c.close();
        if (db != null) db.close();
    }

    void sql(String statement) {
        db.pool().query(statement).execute().await();
    }

    /** The save of {@code e} under the cases' one partition key, its input not kept, not waited for. */
    Future<String> start(Envelope e) {
        return start(e, false);
    }

    Future<String> start(Envelope e, boolean keepInput) {
        return a.intake.save(e, JournalWorld.ingress(e), JournalWorld.FLOW, "K-1", keepInput, JournalWorld.undecided());
    }

    /** A save that succeeded answering {@code id}: the fixture's warm-up of the connection's statements, or a case's save. */
    void saved(Future<String> save, String id) {
        assertTrue(settled(save).succeeded(), "saved: " + save.cause());
        assertEquals(id, save.result(), "the save answers its message's id");
    }

    /** An Execute of a statement whose SQL starts with {@code prefix}, case ignored. */
    static Predicate<PgWire.Sent> executing(String prefix) {
        return s -> s.type() == 'E' && s.sql() != null && s.sql().strip().toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    /**
     * The statement a SQL text is: {@link #KEY_LOCK}, {@code insert into <table>} (a CTE's insert too), {@code select from <table>}, or the text upper-cased (BEGIN,
     * COMMIT, ROLLBACK); a Parse keeps its {@code parse } prefix, so a statement prepared again shows in the turn it cost.
     */
    static String head(String sql) {
        if (sql.startsWith("parse ")) return "parse " + head(sql.substring("parse ".length()));
        String s = sql.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith(KEY_LOCK + "(")) return KEY_LOCK;
        if (s.startsWith("insert into ")) return "insert into " + identifier(s.substring("insert into ".length()));
        if (s.startsWith("with ") && s.contains(" insert into ")) return "insert into " + identifier(s.substring(s.indexOf(" insert into ") + " insert into ".length()));
        if (s.startsWith("select ") && s.contains(" from ")) return "select from " + identifier(s.substring(s.indexOf(" from ") + " from ".length()));
        return s.toUpperCase(Locale.ROOT);
    }

    private static String identifier(String s) {
        int end = 0;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) end++;
        return s.substring(0, end);
    }

    static List<String> heads(List<String> statements) {
        return statements.stream().map(PgIntakeRoundTripIT::head).toList();
    }

    /** The save's messages since {@code mark}, one group per wait of the client, each group's statements as {@link #head}s. */
    List<List<String>> turns(int mark) {
        return wire.turnsSince(mark).stream().map(PgIntakeRoundTripIT::heads).toList();
    }

    static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /**
     * The rows of an unkeyed save are sent before any reply to them: with the reply to the first row statement held, every row
     * statement reaches the relay and COMMIT does not; released, the save is three waits — BEGIN alone, the rows, COMMIT alone —
     * one COMMIT and one journal force for its record.
     */
    @Test
    void anUnkeyedSaveSendsItsRowsBeforeAnyRowReply() throws Exception {
        saved(start(a.arrive("m-warm", bytes(3_000, 1))), "m-warm");   // the connection has run the save's statements once
        Path segment = a.files.fileOf("m-warm");
        int forcesBefore = a.files.forces(segment);

        Envelope in = a.arrive("m-rows", bytes(3_000, 2));
        PgWire.HeldReply firstRow = wire.holdReplyTo(executing("insert into messages"));
        int mark = wire.mark();
        Future<String> save = start(in);
        firstRow.awaitReached();
        wire.awaitQuiet();   // the relay has read everything the client sent while no reply to a row reached it
        assertEquals(concat(List.of("BEGIN"), ROWS), heads(wire.statementsSince(mark)),
                "with the first row's reply held, every row statement reached the relay and COMMIT did not: " + wire.since(mark));
        assertFalse(save.isComplete(), "the save waits for its rows' replies");

        firstRow.release();
        saved(save, "m-rows");
        assertEquals(List.of(List.of("BEGIN"), ROWS, List.of("COMMIT")), turns(mark),
                "3 waits: BEGIN awaited alone, the rows sent together, COMMIT alone after their replies: " + wire.since(mark));
        assertEquals(1, heads(wire.statementsSince(mark)).stream().filter("COMMIT"::equals).count(), "one COMMIT");
        assertEquals(segment, a.files.fileOf("m-rows"), "fixture: both records in one segment file");
        assertEquals(1, a.files.forces(segment) - forcesBefore, "one journal force for the save's record");
        assertEquals(1, count(db.pool(), "select count(*) from messages where id = 'm-rows'"), "its ingress row committed");
        assertEquals(1, count(db.pool(), "select count(*) from work where id = 'm-rows'"), "and its item");
    }

    /**
     * Review r3 (R04, PgWorkQueue.lockKeys): a save with no partition key takes no key lock — nothing is sent for none — so its
     * rows turn is the record and the item only: three waits, no key-lock statement.
     */
    @Test
    void aSaveWithNoPartitionKeySendsNoKeyLock() {
        Envelope warm = a.arrive("m-warm", bytes(3_000, 21));
        saved(a.intake.save(warm, JournalWorld.ingress(warm), JournalWorld.FLOW, null, false, JournalWorld.undecided()), "m-warm");   // run once

        int mark = wire.mark();
        Envelope in = a.arrive("m-free", bytes(3_000, 22));
        saved(a.intake.save(in, JournalWorld.ingress(in), JournalWorld.FLOW, null, false, JournalWorld.undecided()), "m-free");
        assertEquals(List.of(List.of("BEGIN"), List.of("insert into messages", "insert into message_keys", "insert into work"), List.of("COMMIT")),
                turns(mark), "3 waits and no key-lock statement for a save without a partition key: " + wire.since(mark));
        assertEquals(0, heads(wire.statementsSince(mark)).stream().filter(KEY_LOCK::equals).count(), "no key lock sent");
        assertEquals(1, count(db.pool(), "select count(*) from work where id = 'm-free' and partition_key is null"), "its item, unkeyed, committed");
    }

    /** A save that keeps its input writes the body's kept ref (KEEP) in the rows turn, after the item: still three waits. */
    @Test
    void aKeptInputJoinsTheRowsTurn() {
        saved(start(a.arrive("m-warm", bytes(3_000, 11)), true), "m-warm");   // KEEP too runs once on the connection

        int mark = wire.mark();
        saved(start(a.arrive("m-kept", bytes(3_000, 12)), true), "m-kept");
        assertEquals(List.of(List.of("BEGIN"), concat(ROWS, List.of("insert into body_refs")), List.of("COMMIT")), turns(mark),
                "3 waits: KEEP sent with the rows, COMMIT alone after its reply: " + wire.since(mark));
        assertEquals(1, count(db.pool(), "select count(*) from body_refs where message_id = 'm-kept'"), "its kept ref committed");
    }

    /** The node's connect options cache prepared statements: the first save on the connection parses its statements, a second none. */
    @Test
    void theNodePoolParsesAStatementOnce() {
        int first = wire.mark();
        saved(start(a.arrive("m-cold", bytes(3_000, 7))), "m-cold");
        assertEquals(ROWS, wire.since(first).stream().filter(s -> s.type() == 'P').map(s -> head(s.sql())).toList(),
                "witness: the first save on the connection parses each of its statements once: " + wire.since(first));

        int second = wire.mark();
        saved(start(a.arrive("m-warm", bytes(3_000, 8))), "m-warm");
        assertEquals(List.of(), wire.since(second).stream().filter(s -> s.type() == 'P').map(s -> head(s.sql())).toList(),
                "the second save on the one connection sends no Parse: " + wire.since(second));
        assertEquals(heads(wire.statementsSince(first)).subList(0, 2 + ROWS.size()), heads(wire.statementsSince(second)), "the same statements");
        assertEquals(1, wire.since(first).stream().map(PgWire.Sent::conn).distinct().count(), "fixture: both saves on the one connection");
    }

    /**
     * A refused row statement — message_keys, after the messages insert of the same turn succeeded — rolls the save back: the
     * save fails with the refusal, as today, nothing of it is visible, the turn was sent whole and ROLLBACK follows its replies;
     * no COMMIT is sent, and the save leaves the open saves.
     */
    @Test
    void aFailedRowStatementRollsBackAndCommitsNothing() throws Exception {
        saved(start(a.arrive("m-warm", bytes(3_000, 9))), "m-warm");
        sql("create function refuse_keys() returns trigger language plpgsql as $$ begin raise exception 'message_keys refused by the test'; end $$");
        sql("create trigger refuse_keys before insert on message_keys for each statement execute function refuse_keys()");

        int mark = wire.mark();
        Future<String> refused = start(a.arrive("m-refused", bytes(3_000, 10)));
        assertTrue(settled(refused).failed(), "a refused row is a failed save: " + refused);
        assertTrue(causes(refused.cause()).stream().anyMatch(c -> c.contains("message_keys refused by the test")),
                "failed with the refusal, not a later statement's aborted transaction: " + causes(refused.cause()));
        assertEquals(concat(concat(List.of("BEGIN"), ROWS), List.of("ROLLBACK")), heads(wire.statementsSince(mark)),
                "the rows sent together, then ROLLBACK, never COMMIT: " + wire.since(mark));
        assertEquals(List.of(List.of("BEGIN"), ROWS, List.of("ROLLBACK")), turns(mark), "ROLLBACK after every reply of the rows: " + wire.since(mark));
        assertEquals(0, count(db.pool(), "select count(*) from messages where id = 'm-refused'"), "no ingress row: its insert rolled back");
        assertEquals(0, count(db.pool(), "select count(*) from message_keys where message_id = 'm-refused'"), "no key row");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-refused'"), "no item");
        assertEquals(List.of(), List.copyOf(a.intake.openSaves()), "the save left the open saves once its rollback was acknowledged");
    }

    /**
     * Review r1 (2): the record's statements answer in their order — a refused messages insert, its input kept, fails the save
     * with that refusal, never the 25P02 of message_keys, the item or KEEP sent after it in the same turn.
     */
    @Test
    void aRefusedRecordFailsWithItsOwnCauseWhenItsInputIsKept() {
        saved(start(a.arrive("m-warm", bytes(3_000, 16)), true), "m-warm");
        sql("create function refuse_messages() returns trigger language plpgsql as $$ begin raise exception 'messages refused by the test'; end $$");
        sql("create trigger refuse_messages before insert on messages for each statement execute function refuse_messages()");

        Future<String> refused = start(a.arrive("m-record", bytes(3_000, 17)), true);
        assertTrue(settled(refused).failed(), "a refused record is a failed save: " + refused);
        List<String> causes = causes(refused.cause());
        assertTrue(causes.stream().anyMatch(c -> c.contains("messages refused by the test")), "failed with the messages insert's refusal: " + causes);
        assertTrue(causes.stream().noneMatch(c -> c.contains("25P02")), "never with a later statement's aborted transaction: " + causes);
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-record'"), "no item");
        assertEquals(0, count(db.pool(), "select count(*) from body_refs where message_id = 'm-record'"), "no kept ref");
    }
}
