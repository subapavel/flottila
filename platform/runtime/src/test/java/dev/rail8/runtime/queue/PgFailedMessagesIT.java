package dev.rail8.runtime.queue;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BodyLostException;
import dev.rail8.api.Envelope;
import dev.rail8.api.Key;
import dev.rail8.api.Props;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The operator's acts on a real Postgres beyond the contract ({@link PgFailedMessages}). Each act that commits writes one
 * {@code audit} row — action {@code message.retry} or {@code message.cancel}, subject {@code message/<id>}, the actor's name — with
 * its detail; an act that answers anything else, or fails, writes none. A retry that would add a row from a kept ref that is gone
 * fails {@link BlobNotFoundException} naming {@code <id>/0/body} — {@link BodyLostException} when an accepted loss gave the ref up —
 * unless a work row of the id is unsettled, and changes nothing. A retry finds only a worker's park at step 1 after the attempt
 * read, of that flow, whatever other prop it carries. A stopped row whose body is lost is not retried (it cannot run) but is
 * cancelled. An end of a message that is not parked marks the oldest row of the flow its arrival record names, and refuses a
 * lost one. A keyed act takes its key's transaction advisory lock, as every keyed insert and delete does: while another
 * transaction holds it the act waits. An act that waits on a DLQ row another transaction deletes finds it gone.
 */
@Timeout(120)
class PgFailedMessagesIT {

    static final String NODE = "node-a";
    static final Duration LEASE = Duration.ofSeconds(30);
    static final FailedMessages.Actor BY = new FailedMessages.Actor("ops", "10.0.0.7");
    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    PgWorkQueue rows;
    PgFailedMessages failed;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void clean() {
        pool.query("truncate work, dlq, body_refs, journal_segments, audit, messages, retiring, deployments").execute().await();
        rows = new PgWorkQueue(pool, NODE);
        failed = new PgFailedMessages(pool, rows);
    }

    static <T> T await(Future<T> f) throws Exception {
        return f.await(20, TimeUnit.SECONDS);
    }

    /** {@code id} of flow t parked by its worker after attempt 2 with no work row, its kept ref on {@link #NODE}. */
    Parked parked(String id) {
        Parked p = ParkedRows.byTheWorker(id, "t", 2);
        ParkedRows.park(pool, p, NODE);
        return p;
    }

    /** {@code id} of flow t under key K, handed out as its key's head and stopped after attempt 1; its hand-out. */
    WorkQueue.Item stopped(String id) throws Exception {
        WorkQueue.Item item = await(rows.enqueueLeased(id, "t", 1, "K", NODE, LEASE)).orElseThrow(() -> new AssertionError("fixture: " + id + " heads K"));
        ParkedRows.keepRef(pool, id, NODE);
        assertTrue(await(rows.stop(item.seq(), item.attempts(), ParkedRows.byTheWorker(id, "t", 1, Optional.of("K")))), "fixture: " + id + " stopped");
        return item;
    }

    void unchanged(Parked p) throws Exception {
        assertEquals(Optional.of(p), await(new PgDlq(pool).get(p.messageId())), "the DLQ row stays as it was");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "no work row");
        assertEquals(0, audits(), "no audit row");
    }

    long audits() {
        return ParkedRows.count(pool, "select count(*) from audit");
    }

    /** The one audit row: actor, action, subject and detail. */
    List<Object> theAudit() {
        List<Object> found = new java.util.ArrayList<>();
        for (Row r : pool.query("select actor, action, subject, detail from audit").execute().await()) {
            found.addAll(List.of(r.getString(0), r.getString(1), r.getString(2), new JsonObject(r.getValue(3).toString())));
        }
        assertEquals(4, found.size(), "one audit row: " + found);
        return found;
    }

    @Test
    void aRetryRecordsItsFlowAttemptKeySeqAndRemote() throws Exception {
        WorkQueue.Item k1 = stopped("k1");
        pool.query("update work set body_node = 'node-b' where id = 'k1'").execute().await();
        FailedMessages.Done done = assertInstanceOf(FailedMessages.Done.class, await(failed.retry("k1", "t", "K", 1, BY)));
        assertEquals(Optional.of("node-b"), done.node(), "the node holding the row's body");
        assertEquals(List.of("ops", "message.retry", "message/k1",
                        new JsonObject().put("flow", "t").put("attempt", 2).put("partitionKey", "K").put("seq", k1.seq()).put("remote", "10.0.0.7")),
                theAudit());
        assertEquals(1, ParkedRows.count(pool, "select count(*) from audit where id = $1", done.event().id()), "the event is the audit row");
    }

    @Test
    void aCancelRecordsTheParkWithItsFailureCut() throws Exception {
        stopped("k1");
        rows.enqueue("k2", "t", 1, "K").await();
        pool.query("update work set body_node = null where id = 'k2'").execute().await();
        String error = "e".repeat(FailedMessages.ERROR_MAX + 1);
        pool.preparedQuery("update dlq set error = $1 where id = 'k1'").execute(Tuple.of(error)).await();
        Parked p = await(new PgDlq(pool).get("k1")).orElseThrow();

        FailedMessages.Done done = assertInstanceOf(FailedMessages.Done.class, await(failed.cancel("k1", BY, Optional.of("sent by hand"))));
        assertEquals(List.of("ops", "message.cancel", "message/k1", new JsonObject().put("flow", "t").put("step", 1).put("stepName", p.stepName())
                        .put("parkedAt", p.parkedAt().toString()).put("partitionKey", "K")
                        .put("bodyHash", p.bodyHash().algorithm() + ":" + p.bodyHash().hex()).put("bodySize", p.bodySize())
                        .put("reason", "sent by hand").put("remote", "10.0.0.7").put("error", error.substring(0, FailedMessages.ERROR_MAX)).put("errorCut", true)),
                theAudit());
        assertEquals(Optional.empty(), done.node(), "k2 names no body node: none to tell");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where id = 'k2'"), "the key's next row stays");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work where id = 'k1'"), "the stopped row is gone");
    }

    @Test
    void aCancelNamesTheNodeOfTheKeysNextRow() throws Exception {
        stopped("k1");
        rows.enqueue("k2", "t", 1, "K").await();
        pool.query("update work set body_node = 'node-b' where id = 'k2'").execute().await();

        FailedMessages.Done done = assertInstanceOf(FailedMessages.Done.class, await(failed.cancel("k1", BY, Optional.empty())));
        assertEquals(Optional.of("node-b"), done.node(), "the node holding the body of k2, which runs now");
        assertEquals(null, ((JsonObject) theAudit().get(3)).getValue("reason"), "no reason given: none recorded");
    }

    /** A stopped row whose body an accepted loss gave up cannot run: a retry is refused as unsettled; a cancel ends it. */
    @Test
    void aLostStoppedRowIsNotRetriedButIsCancelled() throws Exception {
        WorkQueue.Item k1 = stopped("k1");
        pool.preparedQuery("update work set lost_at = now() where seq = $1").execute(Tuple.of(k1.seq())).await();

        assertEquals(new FailedMessages.Unsettled(k1.seq()), await(failed.retry("k1", "t", "K", 1, BY)), "a retry is refused");
        assertEquals(0, audits(), "and writes nothing");
        assertTrue(await(new PgDlq(pool).get("k1")).isPresent(), "k1 stays parked");
        assertInstanceOf(FailedMessages.Done.class, await(failed.cancel("k1", BY, Optional.empty())), "a cancel ends it");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "its lost row is gone with it");
    }

    /** {@code id} arrived in {@code flow}: its arrival record, its {@code messages} row at step 0. */
    void arrived(String id, String flow) {
        pool.preparedQuery("insert into messages(id, step, flow, body_hash) values ($1, 0, $2, $3)")
                .execute(Tuple.of(id, flow, io.vertx.core.buffer.Buffer.buffer(new byte[Long.BYTES]))).await();
    }

    /**
     * An end of a message that is not parked reads its flow off its arrival record and marks the oldest row of that flow alone,
     * auditing the flow, step, key, seq, reason and remote. Without an arrival record nothing is found; a lost row is not ended.
     */
    @Test
    void anEndMarksTheRowOfItsArrivalsFlowAndRefusesALostOne() throws Exception {
        rows.enqueue("m-1", "g", 1, null).await();
        rows.enqueue("m-1", "t", 1, "K").await();
        assertSame(FailedMessages.NotParked.INSTANCE, await(failed.end("m-1", BY, Optional.empty())), "no arrival record: no flow to read");
        arrived("m-1", "t");

        FailedMessages.Stopping s = assertInstanceOf(FailedMessages.Stopping.class, await(failed.end("m-1", BY, Optional.of("wrong order"))));
        long seq = ParkedRows.count(pool, "select seq from work where id = 'm-1' and flow = 't'");
        assertEquals(List.of("ops", "message.cancel", "message/m-1", new JsonObject().put("flow", "t").put("step", 1).put("partitionKey", "K")
                .put("seq", seq).put("reason", "wrong order").put("remote", "10.0.0.7")), theAudit());
        assertEquals(s.event().id(), ParkedRows.count(pool, "select cancel_audit from work where seq = $1", seq), "the row is marked with it");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work where flow = 'g' and cancel_audit is not null"), "the older row of flow g is not");

        rows.enqueue("m-2", "t", 1, null).await();
        arrived("m-2", "t");
        pool.query("update work set lost_at = now() where id = 'm-2'").execute().await();
        long lost = ParkedRows.count(pool, "select seq from work where id = 'm-2'");
        assertEquals(new FailedMessages.Unsettled(lost), await(failed.end("m-2", BY, Optional.empty())), "a lost row is not ended");
        assertEquals(1, audits(), "and writes nothing");
    }

    /**
     * A retry through a queue with admission, of a message whose flow no record runs at-least-once, no mark retires and the node
     * does not name, fails {@link NotAdmittedException} naming the flow and changes nothing; once a mark retires the flow, the
     * retry goes through.
     */
    @Test
    void aRetryOfAFlowThePoolNoLongerRunsFailsNotAdmittedAndChangesNothing() throws Exception {
        Parked p = parked("m-1");
        PgFailedMessages admitting = new PgFailedMessages(pool, new PgWorkQueue(pool, NODE, null, new FakeTimer(FakeClock.at(java.time.Instant.EPOCH)), Set::of));

        Throwable thrown = assertThrows(Throwable.class, () -> await(admitting.retry("m-1", "t", null, 2, BY)));

        assertEquals(List.of("t"), assertInstanceOf(NotAdmittedException.class, thrown).flows(), "names the flow");
        unchanged(p);
        mark(pool, "t");
        assertInstanceOf(FailedMessages.Done.class, await(admitting.retry("m-1", "t", null, 2, BY)), "a retired flow is admitted: its drain runs the retry");
    }

    /** Marks {@code flow} retired, as the deploy store marks it. */
    static void mark(Pool on, String flow) {
        on.preparedQuery("insert into retiring (flow, package, version, repository, commit, content, dependencies, deployed_at, marked_at) "
                + "values ($1, 'shop', '1.0.0', 'shop', repeat('1', 40), 'sha256:' || repeat('1', 64), '[]'::jsonb, now(), now())")
                .execute(Tuple.of(flow)).await();
    }

    @Test
    void aRetryWhoseKeptRefIsGoneFailsBlobNotFoundAndChangesNothing() throws Exception {
        Parked p = parked("m-1");
        pool.preparedQuery("delete from body_refs where message_id = $1").execute(Tuple.of("m-1")).await();
        Throwable thrown = assertThrows(Throwable.class, () -> await(failed.retry("m-1", "t", null, 2, BY)));
        assertEquals(BlobKey.body("m-1", 0), assertInstanceOf(BlobNotFoundException.class, thrown).key(), "names the ingress body");
        unchanged(p);
    }

    /**
     * The guard is read before the lost body: a DLQ row whose kept ref is gone, beside a work row of its id whose settle has not
     * landed (leased), is refused as unsettled naming that row, and nothing changes.
     */
    @Test
    void aRetryWhoseKeptRefIsGoneBesideAnUnsettledWorkRowIsUnsettled() throws Exception {
        Parked p = parked("m-1");
        rows.enqueue("m-1", "t", 1, null).await();
        List<WorkQueue.Item> taken = rows.claim(NODE, "t", 1, LEASE).await();
        assertEquals(1, taken.size(), "fixture: the id's work row handed out, its settle not landed: " + taken);
        long unsettled = taken.get(0).seq();
        pool.preparedQuery("delete from body_refs where message_id = $1").execute(Tuple.of("m-1")).await();

        assertEquals(new FailedMessages.Unsettled(unsettled), await(failed.retry("m-1", "t", null, 2, BY)), "refused as unsettled, naming the work row");
        assertEquals(Optional.of(p), await(new PgDlq(pool).get("m-1")), "the DLQ row stays as it was");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work"), "no work row written");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where seq = $1 and attempts = 1 and locked_by = $2", unsettled, NODE),
                "the unsettled row as it was");
        assertEquals(0, audits(), "no audit row");
    }

    @Test
    void aRetryWhoseKeptRefWasGivenUpFailsBodyLostAndChangesNothing() throws Exception {
        Parked p = parked("m-1");
        pool.preparedQuery("update body_refs set lost_at = now() where message_id = $1").execute(Tuple.of("m-1")).await();
        assertInstanceOf(BodyLostException.class, assertThrows(Throwable.class, () -> await(failed.retry("m-1", "t", null, 2, BY))));
        unchanged(p);
    }

    /** A retry acts only on the park its worker wrote at step 1 after the attempt read, in that flow. */
    @Test
    void aRetryFindsOnlyTheParkItRead() throws Exception {
        Parked p = parked("m-1");
        assertSame(FailedMessages.NotParked.INSTANCE, await(failed.retry("m-1", "other", null, 2, BY)), "another flow");
        assertSame(FailedMessages.NotParked.INSTANCE, await(failed.retry("m-1", "t", null, 3, BY)), "another attempt than the one parked");
        unchanged(p);
        Parked step2 = ParkedRows.row("m-2", "t", 2, Props.empty().with(Envelope.ATTEMPT, 2));
        ParkedRows.park(pool, step2, NODE);
        assertSame(FailedMessages.NotParked.INSTANCE, await(failed.retry("m-2", "t", null, 2, BY)), "a row at step 2");
        Parked none = ParkedRows.row("m-3", "t", 1, Props.empty().with(RETIRED, "m-0"));
        ParkedRows.park(pool, none, NODE);
        for (int k : new int[] {0, 1, 2, 3}) {
            assertSame(FailedMessages.NotParked.INSTANCE, await(failed.retry("m-3", "t", null, k, BY)), "a row without an attempt, at k = " + k);
        }
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "no work row");
        assertEquals(0, audits(), "no audit row");
        assertInstanceOf(FailedMessages.Done.class, await(failed.retry("m-1", "t", null, 2, BY)), "witness: the park read retries");
    }

    /** A prop a removed feature wrote; inert: a DLQ row written before keeps it as data. */
    static final Key<String> RETIRED = new Key<>("replayOf");

    /** A worker's park at step 1 after attempt k is retried whatever other prop it carries: one work row at step 1 with attempts k. */
    @Test
    void aWorkersParkCarryingAnotherPropIsRetried() throws Exception {
        ParkedRows.park(pool, ParkedRows.row("m-1", "t", 1, Props.empty().with(Envelope.ATTEMPT, 2).with(RETIRED, "m-0")), NODE);
        assertInstanceOf(FailedMessages.Done.class, await(failed.retry("m-1", "t", null, 2, BY)), "retried, whatever else it carries");
        assertEquals(Optional.empty(), await(new PgDlq(pool).get("m-1")), "its DLQ row is gone");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where id = 'm-1' and flow = 't' and step = 1 and attempts = 2"),
                "the id's one row at step 1 with attempts k = 2");
    }

    @Test
    void aKeyedRetryWaitsForItsKeysLock() throws Exception {
        parked("m-1");
        Future<FailedMessages.Act> retried = whileTheKeyIsLocked(() -> failed.retry("m-1", "t", "K", 2, BY),
                () -> assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "nothing inserted while it waits"));
        assertInstanceOf(FailedMessages.Done.class, await(retried), "retried once the lock is released");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where id = 'm-1' and partition_key = 'K'"), "under its key");
    }

    @Test
    void aCancelOfAStoppedRowWaitsForItsKeysLock() throws Exception {
        stopped("k1");
        Future<FailedMessages.Act> cancelled = whileTheKeyIsLocked(() -> failed.cancel("k1", BY, Optional.empty()),
                () -> assertEquals(1, ParkedRows.count(pool, "select count(*) from work"), "nothing deleted while it waits"));
        assertInstanceOf(FailedMessages.Done.class, await(cancelled), "cancelled once the lock is released");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "the stopped row is gone");
    }

    /** {@code act} started while another transaction holds key K's lock of flow t: it waits, {@code meanwhile} holds, then it goes on. */
    Future<FailedMessages.Act> whileTheKeyIsLocked(java.util.function.Supplier<Future<FailedMessages.Act>> act, Runnable meanwhile) throws Exception {
        SqlConnection holder = pool.getConnection().await();
        try {
            Transaction tx = holder.begin().await();
            holder.preparedQuery("select pg_advisory_xact_lock($1, hashtext($2))").execute(Tuple.of(PgWorkQueue.KEY_LOCK_CLASS, "t\u001FK")).await();
            Future<FailedMessages.Act> acting = act.get();
            until(() -> ParkedRows.count(pool, "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                    + " and wait_event = 'advisory'") == 1, "the act waits on the key's lock");
            assertFalse(acting.isComplete(), "not before the lock is released");
            meanwhile.run();
            tx.commit().await();
            return acting;
        } finally {
            holder.close().await();
        }
    }

    /**
     * A removal of the DLQ row in flight (another act's, uncommitted) when the retry comes: the retry waits on the row's lock and,
     * once the removal committed, finds no row — not parked, not a failure, nothing written.
     */
    @Test
    void aRowRemovedWhileTheRetryWaitsOnItIsNotParked() throws Exception {
        parked("m-1");
        SqlConnection remover = pool.getConnection().await();
        Future<FailedMessages.Act> retried;
        try {
            Transaction tx = remover.begin().await();
            remover.preparedQuery("delete from dlq where id = $1").execute(Tuple.of("m-1")).await();
            retried = failed.retry("m-1", "t", null, 2, BY);
            until(() -> ParkedRows.count(pool, "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'") == 1,
                    "the retry waits on the removal's row lock");
            tx.commit().await();
        } finally {
            remover.close().await();
        }
        assertSame(FailedMessages.NotParked.INSTANCE, await(retried), "the row went meanwhile");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "no work row");
        assertEquals(0, audits(), "no audit row");
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
        }
    }
}
