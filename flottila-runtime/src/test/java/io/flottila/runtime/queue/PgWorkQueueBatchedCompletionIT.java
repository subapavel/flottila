package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.journal.PgWire;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-7 C2–C4 (D-122, F-31): the Postgres queue built with the completion batcher, {@code new PgWorkQueue(pool, node, store, timer)},
 * completes unkeyed rows by one autocommit DELETE for up to 256 members: the first completion goes at once when no batch is in flight,
 * later ones gather behind it (window PT0S, no timer), 2 statements in flight at most, 8192 members waiting at most, a member that
 * finds no room waits ≤ PT5S for it, then fails, is counted refused and is left to its lease. Each row is fenced by its attempts
 * token; a held, keyed, re-claimed, never-claimed or unknown row answers STALE and its neighbours complete. A failed statement fails
 * every member with its cause, no retry. {@code close()} refuses new members and answers every member already handed over.
 *
 * <p>Seams (test-author): the queue's pool connects through a {@link PgWire} relay, so every DELETE it sends is counted at the wire
 * (an Execute whose portal's statement starts {@code delete from work}); fixtures go through a direct pool. A statement-level
 * {@code AFTER DELETE} trigger with a transition table logs how many rows each DELETE removed ({@code delete_sizes}) — the per-statement
 * member count, read from Postgres. A row-level {@code BEFORE DELETE} trigger refuses any row whose id starts {@code poison} (a
 * statement that really fails, its rows untouched). Gathering is staged without sleeps: a fixture session holds {@code lock table work
 * in share mode} (or one row {@code for update}) so the first batch waits in Postgres while the rest gather behind it in the batcher.
 * The fake timer is never advanced except to drive maxWait: a batcher that waited on a window timer never answers here.
 *
 * <p>The API assumed (CORE-7 packet; the choices marked * are the test-author's, for approval): {@code PgWorkQueue(Pool, String node,
 * TieredBlobStore store, Timer timer)}, {@code store} null allowed*; {@code Future<Completion> completeUnkeyed(long seq, int attempts)};
 * {@code completions()} answering a record with accessors {@code statements(), members(), stale(), refused()} (numbers, int or long*);
 * {@code Future<Void> close()}* completing once every member handed over was answered. {@code members} counts the members sent in
 * statements*.
 */
@Timeout(120)
class PgWorkQueueBatchedCompletionIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final String FLOW = "orders";
    static final String NODE = "n1";
    static final Duration LEASE = Duration.ofMinutes(5);
    static Vertx vertx;
    static Pool direct;
    static PgWorkQueue rows;   // fixtures: enqueue, claim, hold, through the direct pool

    PgWire wire;
    Pool pool;
    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-11T10:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);
    PgWorkQueue queue;
    final List<Holder> holders = new ArrayList<>();
    Future<Void> closed;   // the case's own close, if it closed the queue

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        direct = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        rows = new PgWorkQueue(direct);
        sql("create table delete_sizes(n bigserial primary key, deleted int not null)");
        sql("create function log_delete_size() returns trigger language plpgsql as $$ begin "
                + "insert into delete_sizes(deleted) select count(*) from gone; return null; end $$");
        sql("create trigger log_delete_size after delete on work referencing old table as gone for each statement execute function log_delete_size()");
        sql("create function refuse_poison() returns trigger language plpgsql as $$ begin "
                + "if old.id like 'poison%' then raise exception 'poison refused by the test'; end if; return old; end $$");
        sql("create trigger refuse_poison before delete on work for each row execute function refuse_poison()");
    }

    @AfterAll
    static void stop() {
        direct.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() throws Exception {
        sql("truncate work, nodes, delete_sizes");
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost("127.0.0.1").setPort(wire.port()).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        queue = new PgWorkQueue(pool, NODE, null, timer);
    }

    @AfterEach
    void close() throws Exception {
        for (Holder h : holders) h.release();
        await(closed != null ? closed : queue.close());
        pool.close().await();
        wire.close();
    }

    // --- fixtures

    static void sql(String statement) {
        direct.query(statement).execute().await();
    }

    static <T> T await(Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("still pending after 20 s: " + f, e);
        }
    }

    /** {@code f} once settled, failed or not (20 s bound). */
    static <T> AsyncResult<T> settled(Future<T> f) {
        return await(f.transform(Future::succeededFuture));
    }

    /** Spins (never sleeping) on real I/O until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** Unkeyed rows {@code ids} enqueued and claimed by {@link #NODE}: each at attempts 1, in the order given. */
    static List<WorkQueue.Item> claimed(String... ids) {
        return claimedUnder(null, ids);
    }

    static List<WorkQueue.Item> claimedUnder(String key, String... ids) {
        for (String id : ids) await(rows.enqueue(id, FLOW, 1, key));
        List<WorkQueue.Item> items = await(rows.claim(NODE, FLOW, ids.length, LEASE));
        assertEquals(List.of(ids), items.stream().map(WorkQueue.Item::messageId).toList(), "fixture: claimed in order");
        items.forEach(i -> assertEquals(1, i.attempts(), "fixture: first hand-out"));
        return items;
    }

    /** {@code n} unkeyed rows {@code <prefix>1..n} written as a claim by {@link #NODE} leaves them (attempts 1, leased): their seqs in order. */
    static List<Long> bulkClaimed(String prefix, int n) {
        List<Long> seqs = new ArrayList<>();
        for (Row r : direct.preparedQuery("insert into work(id, flow, step, locked_by, locked_until, attempts) select $1 || g, $2, 1, $3, "
                + "now() + interval '5 minutes', 1 from generate_series(1, $4) g order by g returning seq").execute(Tuple.of(prefix, FLOW, NODE, n)).await()) {
            seqs.add(r.getLong(0));
        }
        seqs.sort(null);
        assertEquals(n, seqs.size(), "fixture: " + n + " rows");
        return seqs;
    }

    /** "attempts locked_by held" of the row of {@code seq}, or "gone". */
    static String row(long seq) {
        for (Row r : direct.preparedQuery("select attempts, locked_by, held_at is not null from work where seq = $1").execute(Tuple.of(seq)).await()) {
            return r.getInteger(0) + " " + r.getString(1) + (r.getBoolean(2) ? " held" : "");
        }
        return "gone";
    }

    static long rowCount() {
        return direct.query("select count(*) from work").execute().await().iterator().next().getLong(0);
    }

    /** The rows each DELETE removed, sorted (statement order across two in flight is not fixed). */
    static List<Integer> sizes() {
        List<Integer> sizes = new ArrayList<>();
        for (Row r : direct.query("select deleted from delete_sizes order by deleted").execute().await()) sizes.add(r.getInteger(0));
        return sizes;
    }

    /** The DELETEs the queue's pool executed after {@code mark}. */
    int deletes(int mark) {
        return (int) wire.since(mark).stream().filter(s -> s.type() == 'E' && s.sql() != null
                && s.sql().strip().toLowerCase(Locale.ROOT).startsWith("delete from work")).count();
    }

    /** Backends of the database waiting on a lock in a DELETE of work. */
    static int deletesWaiting() {
        return direct.query("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' "
                + "and query ilike 'delete from work%'").execute().await().iterator().next().getInteger(0);
    }

    /** A fixture session's open transaction holding a lock; rolled back by {@link #release}. */
    final class Holder {
        final SqlConnection conn;
        final Transaction tx;
        boolean released;

        Holder(String lock, Tuple args) {
            conn = await(direct.getConnection());
            tx = await(conn.begin());
            await(conn.preparedQuery(lock).execute(args));
            holders.add(this);
        }

        void release() {
            if (released) return;
            released = true;
            await(tx.rollback());
            await(conn.close());
        }
    }

    /** {@code lock table work in share mode}: every DELETE of work waits until {@link Holder#release}. */
    Holder shareLock() {
        return new Holder("lock table work in share mode", Tuple.tuple());
    }

    /** The row of {@code seq} locked {@code for update}: only a DELETE of that row waits. */
    Holder rowLock(long seq) {
        return new Holder("select 1 from work where seq = $1 for update", Tuple.of(seq));
    }

    static final WorkQueue.Completion DONE = new WorkQueue.Completion(true, Optional.empty(), Optional.empty());

    Future<WorkQueue.Completion> complete(WorkQueue.Item item) {
        return queue.completeUnkeyed(item.seq(), item.attempts());
    }

    // --- the cases

    /**
     * The acceptance (BACKLOG done-when, C5): a completion sent at once waits in Postgres behind the share lock; 100 more gather
     * behind it; released, exactly 2 DELETEs ran — 1 row, then 100 — every member done and every row gone. Today's single
     * statement path runs 101.
     */
    @Test
    void nCompletionsGatheredBehindABatchInFlightAreOneDelete() {
        String[] ids = new String[101];
        for (int i = 0; i < ids.length; i++) ids[i] = "m-" + i;
        List<WorkQueue.Item> items = claimed(ids);
        Holder lock = shareLock();
        int mark = wire.mark();

        List<Future<WorkQueue.Completion>> answers = new ArrayList<>();
        answers.add(complete(items.get(0)));
        until(() -> deletesWaiting() == 1, () -> "the first completion is sent at once and waits on the lock");
        for (WorkQueue.Item item : items.subList(1, items.size())) answers.add(complete(item));
        assertFalse(answers.stream().anyMatch(Future::isComplete), "nothing answers while the first batch waits");
        lock.release();

        for (Future<WorkQueue.Completion> a : answers) assertEquals(DONE, await(a), "every member done");
        assertEquals(2, deletes(mark), "one DELETE for the first, one for the 100 gathered behind it (the single-row path: 101)");
        assertEquals(List.of(1, 100), sizes(), "the rows each DELETE removed");
        assertEquals(0, rowCount(), "every row gone");
        var c = queue.completions();
        assertEquals(2, c.statements(), "statements: " + c);
        assertEquals(101, c.members(), "members: " + c);
        assertEquals(0, c.stale(), "stale: " + c);
        assertEquals(0, c.refused(), "refused: " + c);
        assertEquals(0, timer.pending(), "no timer: the window is PT0S");
    }

    /** Window PT0S: with no batch in flight a completion is sent at once — answered while the fake timer never moves. */
    @Test
    void anIdleCompletionIsSentAtOnce() {
        List<WorkQueue.Item> items = claimed("a", "b");
        int mark = wire.mark();
        assertEquals(DONE, await(complete(items.get(0))), "answered without any timer firing");
        assertEquals(0, timer.pending(), "no window timer armed");
        assertEquals(1, deletes(mark), "one DELETE");
        assertEquals(DONE, await(complete(items.get(1))), "the next idle completion too");
        assertEquals(2, deletes(mark), "one DELETE each: nothing waits for company");
        assertEquals(List.of(1, 1), sizes());
        assertEquals("gone", row(items.get(0).seq()));
        assertEquals("gone", row(items.get(1).seq()));
        var c = queue.completions();
        assertEquals(2, c.statements(), "statements: " + c);
        assertEquals(2, c.members(), "members: " + c);
    }

    /**
     * ≤ 256 members per statement, 2 in flight: the first goes alone and waits on the lock; the next 256 are a full batch and take
     * the second slot; the last 256 wait for a slot. 2·256+1 completions are exactly 3 statements of 1, 256 and 256 rows (a bound of
     * 255 would be 4 statements, 257 or more other sizes).
     */
    @Test
    void aBatchHoldsAtMostMaxMembers() {
        List<Long> seqs = bulkClaimed("big-", 2 * 256 + 1);
        Holder lock = shareLock();
        int mark = wire.mark();
        List<Future<WorkQueue.Completion>> answers = new ArrayList<>();
        for (long seq : seqs) answers.add(queue.completeUnkeyed(seq, 1));
        until(() -> deletesWaiting() == 2, () -> "the first and the full second batch wait on the lock");
        lock.release();
        for (Future<WorkQueue.Completion> a : answers) assertEquals(DONE, await(a));
        assertEquals(3, deletes(mark), "3 statements");
        assertEquals(List.of(1, 256, 256), sizes(), "at most 256 rows a statement");
        assertEquals(0, rowCount());
        var c = queue.completions();
        assertEquals(3, c.statements(), "statements: " + c);
        assertEquals(2 * 256 + 1, c.members(), "members: " + c);
    }

    /**
     * Fenced per row (C2, C4): gathered in one batch with two current rows are a row re-claimed since (its token 1, the row at 2),
     * a held row, a row never handed out (token 0), an unknown seq; those four answer STALE and are as they were, the two complete.
     */
    @Test
    void aStaleHeldOrUnknownMemberAnswersStaleAndItsNeighboursComplete() {
        List<WorkQueue.Item> items = claimed("first", "good-1", "again", "held", "good-2");
        WorkQueue.Item first = items.get(0);
        WorkQueue.Item good1 = items.get(1);
        WorkQueue.Item again = items.get(2);
        WorkQueue.Item held = items.get(3);
        WorkQueue.Item good2 = items.get(4);
        sql("update work set locked_until = now() - interval '1 second' where id = 'again'");
        assertEquals(List.of(2), await(rows.claim("n2", FLOW, 5, LEASE)).stream().map(WorkQueue.Item::attempts).toList(), "fixture: 'again' re-claimed");
        assertTrue(await(rows.hold(held.seq(), 1)), "fixture: held");
        await(rows.enqueue("never", FLOW, 1, null));
        long never = direct.query("select seq from work where id = 'never'").execute().await().iterator().next().getLong(0);
        long unknown = never + 1_000;

        Holder lock = shareLock();
        int mark = wire.mark();
        Future<WorkQueue.Completion> f0 = complete(first);
        until(() -> deletesWaiting() == 1, () -> "the first batch waits on the lock");
        Map<String, Future<WorkQueue.Completion>> answers = new TreeMap<>();
        answers.put("good-1", complete(good1));
        answers.put("again", queue.completeUnkeyed(again.seq(), 1));
        answers.put("held", queue.completeUnkeyed(held.seq(), 1));
        answers.put("never", queue.completeUnkeyed(never, 0));
        answers.put("unknown", queue.completeUnkeyed(unknown, 1));
        answers.put("good-2", complete(good2));
        lock.release();

        assertEquals(DONE, await(f0));
        Map<String, WorkQueue.Completion> got = new TreeMap<>();
        answers.forEach((k, f) -> got.put(k, await(f)));
        assertEquals(new TreeMap<>(Map.of("good-1", DONE, "good-2", DONE, "again", WorkQueue.Completion.STALE, "held", WorkQueue.Completion.STALE,
                "never", WorkQueue.Completion.STALE, "unknown", WorkQueue.Completion.STALE)), got, "each member answered by its own row");
        assertEquals(2, deletes(mark), "the six went in one statement");
        assertEquals(List.of(1, 2), sizes(), "it removed only the two current rows");
        assertEquals("gone", row(good1.seq()));
        assertEquals("gone", row(good2.seq()));
        assertEquals("2 n2", row(again.seq()), "the re-claimed row is its new holder's");
        assertEquals("1 n1 held", row(held.seq()), "the held row stays held");
        assertEquals("0 null", row(never), "the row never handed out is untouched");
        var c = queue.completions();
        assertEquals(4, c.stale(), "stale: " + c);
        assertEquals(7, c.members(), "members: " + c);
    }

    /** C1/C2: completeUnkeyed never deletes a row with a partition key — alone, or in a batch whose unkeyed neighbour completes. */
    @Test
    void aKeyedRowIsNeverDeletedByCompleteUnkeyed() {
        WorkQueue.Item k1 = claimedUnder("K", "k1").getFirst();
        WorkQueue.Item k2 = claimedUnder("L", "k2").getFirst();
        List<WorkQueue.Item> plain = claimed("first", "plain");
        int mark = wire.mark();
        assertEquals(WorkQueue.Completion.STALE, await(queue.completeUnkeyed(k1.seq(), 1)), "alone: a keyed row is stale to completeUnkeyed");

        Holder lock = shareLock();
        Future<WorkQueue.Completion> f0 = complete(plain.get(0));
        until(() -> deletesWaiting() == 1, () -> "the first batch waits on the lock");
        Future<WorkQueue.Completion> keyed = queue.completeUnkeyed(k2.seq(), 1);
        Future<WorkQueue.Completion> neighbour = complete(plain.get(1));
        lock.release();
        assertEquals(DONE, await(f0));
        assertEquals(WorkQueue.Completion.STALE, await(keyed), "in a batch: stale");
        assertEquals(DONE, await(neighbour), "its unkeyed neighbour completes");
        assertEquals(3, deletes(mark), "k1 alone, first alone, the pair together");
        assertEquals("1 n1", row(k1.seq()), "k1 stays as claimed");
        assertEquals("1 n1", row(k2.seq()), "k2 stays as claimed");
        assertEquals(2, queue.completions().stale(), "both keyed calls counted stale: " + queue.completions());
    }

    /**
     * C2: a statement that fails (Postgres refuses the poison row: the whole statement rolls back) fails every member of its batch
     * with the cause and is not retried; each row stays as claimed and, its lease run out, is claimed again (attempt 2).
     */
    @Test
    void aFailedBatchFailsEveryMemberAndEachRerunsAfterItsLease() {
        List<WorkQueue.Item> items = claimed("first", "poison-1", "a", "b");
        Holder lock = shareLock();
        int mark = wire.mark();
        Future<WorkQueue.Completion> f0 = complete(items.get(0));
        until(() -> deletesWaiting() == 1, () -> "the first batch waits on the lock");
        List<Future<WorkQueue.Completion>> batch = items.subList(1, 4).stream().map(this::complete).toList();
        lock.release();

        assertEquals(DONE, await(f0), "the first batch, without the poison, completes");
        for (Future<WorkQueue.Completion> f : batch) {
            AsyncResult<WorkQueue.Completion> r = settled(f);
            assertTrue(r.failed(), "every member of the failed statement fails: " + r.result());
            assertTrue(String.valueOf(r.cause().getMessage()).contains("poison refused by the test"), "with the statement's cause: " + r.cause());
        }
        assertEquals(2, deletes(mark), "the failed statement is not retried");
        for (WorkQueue.Item i : items.subList(1, 4)) assertEquals("1 n1", row(i.seq()), i.messageId() + " stays as claimed, left to its lease");
        assertEquals(0, queue.completions().stale(), "a failure is no stale answer: " + queue.completions());

        sql("update work set locked_until = now() - interval '1 second'");
        List<WorkQueue.Item> rerun = await(rows.claim(NODE, FLOW, 10, LEASE));
        assertEquals(List.of("poison-1 2", "a 2", "b 2"), rerun.stream().map(i -> i.messageId() + " " + i.attempts()).toList(),
                "each reruns after its lease: claimed again at attempt 2");
    }

    /**
     * C4: a completion handed over while the row was its holder's, whose batch reaches Postgres only after another node took the row
     * over (its TAKE bumped attempts to 2), deletes nothing and answers STALE; the takeover's row stays the new holder's.
     */
    @Test
    void aBatchLandingAfterATakeoverDeletesNothing() {
        List<WorkQueue.Item> items = claimed("first", "x");
        WorkQueue.Item x = items.get(1);
        Holder lock = rowLock(items.get(0).seq());
        Future<WorkQueue.Completion> f0 = complete(items.get(0));
        until(() -> deletesWaiting() == 1, () -> "the first batch waits on its row's lock");
        Future<WorkQueue.Completion> late = complete(x);   // handed over at token 1: gathered behind the first
        sql("update work set locked_until = now() - interval '1 second' where seq = " + x.seq());
        List<WorkQueue.Item> taken = await(rows.claim("n2", FLOW, 5, LEASE));
        assertEquals(List.of("x 2"), taken.stream().map(i -> i.messageId() + " " + i.attempts()).toList(), "fixture: n2 took x over");
        assertFalse(late.isComplete(), "fixture: x's batch has not run yet");
        lock.release();

        assertEquals(DONE, await(f0));
        assertEquals(WorkQueue.Completion.STALE, await(late), "the batch landed after the takeover: stale");
        assertEquals("2 n2", row(x.seq()), "nothing deleted: the row is n2's");
        assertEquals(List.of(0, 1), sizes(), "the late statement removed no row");
    }

    /**
     * C3: 1 + 256 in flight (2 statements waiting on the lock) and 8192 waiting fill the batcher: 8449 completions are taken without a
     * deadline; the 8450th waits for room with a PT5S deadline on the timer — not failed at PT4.999S, failed at PT5S, counted refused,
     * its row left as claimed. A completion that then waits gets room when the lock goes and completes, its deadline cancelled.
     */
    @Test
    void aFullBacklogWaitsAtMostMaxWaitThenLeavesTheItem() {
        int held = 1 + 256 + 8192;
        List<Long> seqs = bulkClaimed("load-", held + 2);
        Holder lock = shareLock();
        List<Future<WorkQueue.Completion>> answers = new ArrayList<>();
        for (long seq : seqs.subList(0, held)) answers.add(queue.completeUnkeyed(seq, 1));
        until(() -> deletesWaiting() == 2, () -> "two statements in flight wait on the lock");
        assertEquals(0, timer.pending(), "8449 members are in flight or in the backlog: none waits for room");

        long refusedSeq = seqs.get(held);
        Future<WorkQueue.Completion> refused = queue.completeUnkeyed(refusedSeq, 1);
        assertEquals(1, timer.pending(), "the 8450th waits for room: its deadline armed");
        assertEquals(Duration.ofSeconds(5), timer.nextDelay(), "maxWait PT5S");
        clock.advance(Duration.ofMillis(4_999));
        assertFalse(refused.isComplete(), "still waiting at PT4.999S");
        clock.advance(Duration.ofMillis(1));
        AsyncResult<WorkQueue.Completion> r = settled(refused);
        assertTrue(r.failed(), "at PT5S without room it fails: " + r.result());
        assertEquals(1, queue.completions().refused(), "counted refused: " + queue.completions());

        long laterSeq = seqs.get(held + 1);
        Future<WorkQueue.Completion> later = queue.completeUnkeyed(laterSeq, 1);
        assertEquals(1, timer.pending(), "a later completion waits for room too");
        lock.release();
        assertEquals(DONE, await(later), "room came before its deadline: it completes");
        assertEquals(0, timer.pending(), "its deadline cancelled");
        for (Future<WorkQueue.Completion> a : answers) assertEquals(DONE, await(a));
        assertEquals("1 n1", row(refusedSeq), "the refused member's row is left to its lease, as claimed");
        assertEquals(1, rowCount(), "every other row gone");
        assertEquals(1, queue.completions().refused(), "one refusal: " + queue.completions());
    }

    /**
     * C2: close() refuses a member handed over after it, and answers — through the open pool — the members already handed over:
     * the batch in flight and the one gathered behind it. Its future completes only once they are answered.
     */
    @Test
    void closeAnswersEveryMemberHandedOverAndRefusesLaterOnes() {
        List<WorkQueue.Item> items = claimed("first", "a", "b", "late");
        Holder lock = shareLock();
        Future<WorkQueue.Completion> f0 = complete(items.get(0));
        until(() -> deletesWaiting() == 1, () -> "the first batch waits on the lock");
        Future<WorkQueue.Completion> a = complete(items.get(1));
        Future<WorkQueue.Completion> b = complete(items.get(2));
        closed = queue.close();
        AsyncResult<WorkQueue.Completion> late = settled(complete(items.get(3)));
        assertTrue(late.failed(), "a member after close is refused: " + late.result());
        assertFalse(closed.isComplete(), "close waits for the members handed over before it");
        lock.release();

        await(closed);
        assertTrue(f0.isComplete() && a.isComplete() && b.isComplete(), "every member handed over was answered when close completed");
        assertEquals(DONE, await(f0));
        assertEquals(DONE, await(a), "the gathered members were flushed, not dropped");
        assertEquals(DONE, await(b));
        assertEquals("1 n1", row(items.get(3).seq()), "the refused member's row is left to its lease");
        assertEquals(1, rowCount());
    }
}
