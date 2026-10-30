package io.flottila.runtime.store;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-07 (D-088, F-20): the retention job against a real Postgres. A run fixes its cutoff — the injected clock minus the max age
 * — and deletes, in keyset batches of one transaction each that first try the advisory lock: whole messages whose every row is
 * older than the cutoff and that no work row (held or not) and no DLQ row names, with their key rows; then bodies older than
 * the cutoff that no messages row, work row or DLQ {@code body_key} names; then {@code processed_ids} rows older than the cutoff
 * whose message is gone. DLQ rows never age out. Every case runs on a database of its own (FIX-5); the fake clock stands 400
 * days ahead of real time, so a cutoff taken from the server's {@code now()} would keep every row a case calls old, and every
 * {@code created_at} is seeded explicitly. The rows a case expects are the rows it seeded minus the ones it names as gone.
 *
 * <p>Shapes these cases read: {@code Run.outcome()} by its name (DONE, SKIPPED, FAILED); {@code Run.messages/keys/bodies/
 * processedIds} count deleted rows of their tables, {@code bodyBytes} the deleted bodies' bytes, {@code kept} the messages rows
 * older than the cutoff a run kept, {@code batches} every batch (transaction) of the run, the last, short one of each phase
 * included; {@code Totals} sums the runs' counts and counts the runs ({@code runs}) and the skipped and failed ones.
 */
class RetentionIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static final Duration MAX_AGE = Duration.ofDays(7);
    static final Duration INTERVAL = Duration.ofHours(1);
    static final Duration BOUND = Duration.ofSeconds(20);
    static final Duration DAY = Duration.ofDays(1);
    static final Duration MICRO = Duration.ofNanos(1000);
    /** The payloads row every messages row references (V1's foreign key); retention leaves {@code payloads} alone (P2-07c). */
    static final Buffer HASH = Buffer.buffer(new byte[8]);
    static Vertx vertx;
    static Pool observer;   // on the container's own database: never a session of the case database

    String database;
    Pool pool;   // the retention job's
    Pool db;     // the fixture's: seeds and reads
    FakeClock clock;
    FakeTimer timer;
    Instant cutoff;   // the clock at the case's start minus MAX_AGE
    final List<String> seeded = new ArrayList<>();
    final List<Retention> jobs = new ArrayList<>();
    final List<Pool> pools = new ArrayList<>();
    final List<PgConnection> sessions = new ArrayList<>();
    final Logs logs = new Logs();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        observer = PgBuilder.pool().connectingTo(options(pg.getDatabaseName())).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterAll
    static void stop() {
        observer.close().await();
        vertx.close().await();
        pg.stop();
    }

    static PgConnectOptions options(String database) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void createTheCaseDatabase() {
        database = PgIntakeIT.caseDatabase(pg, "retention_" + cases.incrementAndGet());
        pool = newPool();
        db = newPool();
        // ahead of real time, on a whole second: the cutoff, and a microsecond either side of it, are exact timestamptz values
        clock = FakeClock.at(Instant.now().plus(Duration.ofDays(400)).truncatedTo(ChronoUnit.SECONDS));
        timer = new FakeTimer(clock);
        cutoff = clock.instant().minus(MAX_AGE);
        db.preparedQuery("insert into payloads(hash, size) values ($1, 0)").execute(Tuple.of(HASH)).await();
        logs.attach();
    }

    @AfterEach
    void closeEverything() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        for (PgConnection s : sessions) attempt(failures, () -> s.close().await(BOUND.toSeconds(), TimeUnit.SECONDS));   // a held lock goes with its session
        for (Retention r : jobs) attempt(failures, () -> r.close().await(BOUND.toSeconds(), TimeUnit.SECONDS));
        for (Pool p : pools) attempt(failures, () -> p.close().await(BOUND.toSeconds(), TimeUnit.SECONDS));
        logs.detach();
        if (!failures.isEmpty()) {
            Exception e = new Exception("closing the case failed: " + failures);
            failures.forEach(e::addSuppressed);
            throw e;
        }
    }

    interface Step {
        void run() throws Exception;
    }

    static void attempt(List<Throwable> failures, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    Pool newPool() {
        Pool p = PgIntakeIT.pool(vertx, pg, database, 4);
        pools.add(p);
        return p;
    }

    /** A retention job over the case's pool, clock and timer: max age P7D, interval PT1H, {@code batch} rows per batch. */
    Retention job(int batch) {
        return job(pool, batch);
    }

    Retention job(Pool on, int batch) {
        Retention r = new Retention(on, clock, timer, new Retention.Config(MAX_AGE, INTERVAL, batch));
        jobs.add(r);
        return r;
    }

    /** One run of {@code r}, awaited at most {@link #BOUND}: a run that does not end fails the case. */
    static Retention.Run run(Retention r) {
        return await(r.runOnce(), "the run");
    }

    static <T> T await(Future<T> f, String what) {
        try {
            return f.await(BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException stillRunning) {
            return fail(what + " did not end within " + BOUND);
        }
    }

    /**
     * {@code r.start()} on a virtual thread of its own, bounded: start begins the first run and returns — it never runs a batch
     * on its caller's thread (a start that did would wait here on a case's locked table).
     */
    static void start(Retention r) {
        java.util.concurrent.CompletableFuture<Void> started = new java.util.concurrent.CompletableFuture<>();
        Thread caller = Thread.ofVirtual().name("retention-it-start").start(() -> {
            try {
                r.start();
                started.complete(null);
            } catch (Throwable t) {
                started.completeExceptionally(t);
            }
        });
        try {
            started.get(BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException stillInStart) {
            fail("start() did not return within " + BOUND + ": it waits in " + Arrays.toString(caller.getStackTrace()));
        } catch (java.util.concurrent.ExecutionException e) {
            fail("start() threw", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while start() ran", e);
        }
    }

    static String outcome(Retention.Run r) {
        return r.outcome().name();
    }

    // --- seeding: every row is recorded in `seeded` under its label

    static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    /** A messages row of {@code id} at {@code step} created {@code at}, carrying {@code keys} ({@code name=value}), and its key rows. */
    void message(String id, int step, Instant created, String... keys) {
        JsonObject k = new JsonObject();
        for (String kv : keys) {
            String[] p = kv.split("=", 2);
            k.put(p[0], p[1]);
        }
        db.preparedQuery("insert into messages(id, step, flow, keys, body_hash, body_key, created_at) values ($1, $2, 'shop/orders', $3, $4, $5, $6)")
                .execute(Tuple.of(id, step, k, HASH, id + "/" + step + "/body", at(created))).await();
        seeded.add("messages " + id + "@" + step);
        for (String kv : keys) {
            String[] p = kv.split("=", 2);
            RowSet<Row> added = db.preparedQuery("insert into message_keys(name, value, message_id, step) values ($1, $2, $3, $4) on conflict do nothing")
                    .execute(Tuple.of(p[0], p[1], id, step)).await();
            if (added.rowCount() == 1) seeded.add("key " + id + " " + kv);   // one row per (name, value, message), as the store writes them (V5)
        }
    }

    /** A bodies row {@code id/step/part} of {@code size} bytes created {@code at}. */
    void body(String id, int step, String part, Instant created, int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + id.hashCode());
        db.preparedQuery("insert into bodies(message_id, step, part, bytes, created_at) values ($1, $2, $3, $4, $5)")
                .execute(Tuple.of(id, step, part, Buffer.buffer(bytes), at(created))).await();
        seeded.add("body " + id + "/" + step + "/" + part);
    }

    void body(String id, int step, Instant created) {
        body(id, step, "body", created, 10);
    }

    /** An open work item of {@code id}; a held one has {@code held_at} set (D-055). */
    void work(String id, boolean held) {
        db.preparedQuery("insert into work(id, flow, step, held_at) values ($1, 'shop/orders', 0, $2)")
                .execute(Tuple.tuple().addValue(id).addValue(held ? at(clock.instant()) : null)).await();
        seeded.add("work " + id);
    }

    /** An open DLQ row {@code id} naming {@code bodyKey} (null: none), parked {@code at}. */
    void dlq(String id, String bodyKey, Instant parked) {
        db.preparedQuery("insert into dlq(id, flow, stage, step, error, body_key, parked_at) values ($1, 'shop/orders', 'map', 1, 'boom', $2, $3)")
                .execute(Tuple.tuple().addValue(id).addValue(bodyKey).addValue(at(parked))).await();
        seeded.add("dlq " + id);
    }

    /** The {@code processed_ids} row of {@code messageId}'s dedupe key, created {@code at}. */
    void processed(String messageId, Instant created) {
        db.preparedQuery("insert into processed_ids(scope, key_hash, message_id, created_at) values ('shop/orders', $1, $2, $3)")
                .execute(Tuple.of(Buffer.buffer(sha256(messageId)), messageId, at(created))).await();
        seeded.add("processed " + messageId);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- reading

    /** Every row of the six tables, labelled as {@code seeded} labels them, sorted. */
    List<String> left() {
        List<String> rows = new ArrayList<>();
        for (Row r : db.query("select id, step from messages").execute().await()) rows.add("messages " + r.getString(0) + "@" + r.getInteger(1));
        for (Row r : db.query("select message_id, name, value from message_keys").execute().await()) {
            rows.add("key " + r.getString(0) + " " + r.getString(1) + "=" + r.getString(2));
        }
        for (Row r : db.query("select message_id, step, part from bodies").execute().await()) {
            rows.add("body " + r.getString(0) + "/" + r.getInteger(1) + "/" + r.getString(2));
        }
        for (Row r : db.query("select message_id from processed_ids").execute().await()) rows.add("processed " + r.getString(0));
        for (Row r : db.query("select id from dlq").execute().await()) rows.add("dlq " + r.getString(0));
        for (Row r : db.query("select id from work").execute().await()) rows.add("work " + r.getString(0));
        rows.sort(null);
        return rows;
    }

    /** The seeded rows of message {@code id}: its messages, key, bodies and processed_ids rows (not its work or DLQ rows). */
    List<String> rowsOf(String id) {
        List<String> of = seeded.stream().filter(l -> l.startsWith("messages " + id + "@") || l.startsWith("key " + id + " ")
                || l.startsWith("body " + id + "/") || l.equals("processed " + id)).toList();
        assertFalse(of.isEmpty(), "fixture: rows seeded for " + id);
        return of;
    }

    /** The seeded rows minus {@code gone} (each must have been seeded), sorted. */
    List<String> seededWithout(List<String> gone) {
        for (String g : gone) assertTrue(seeded.contains(g), "fixture: '" + g + "' was seeded: " + seeded);
        List<String> rows = new ArrayList<>(seeded);
        rows.removeAll(gone);
        rows.sort(null);
        return rows;
    }

    List<String> seededWithout(String... gone) {
        return seededWithout(Arrays.asList(gone));
    }

    static List<String> concat(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> l : lists) all.addAll(l);
        return all;
    }

    /** Backends of the case database waiting on a lock of {@code kind} ({@code relation}, {@code advisory}). */
    long waiting(String kind) {
        return observer.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock' and wait_event = $2")
                .execute(Tuple.of(database, kind)).await().iterator().next().getLong(0);
    }

    /** A session of its own holding {@code table} in access exclusive mode until {@link Held#release}: every statement on it waits. */
    Held lock(String table) {
        PgConnection s = PgConnection.connect(vertx, options(database)).await();
        sessions.add(s);
        Transaction tx = s.begin().await();
        s.query("lock table " + table + " in access exclusive mode").execute().await();
        return new Held(tx);
    }

    record Held(Transaction tx) {
        void release() { tx.rollback().await(); }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** The records the store package logs, with the thread that logged each ({@code publish} runs on it). */
    static final class Logs extends Handler {
        record Line(Level level, String text, String thread, boolean virtual, Throwable thrown) {
            /** The text and the thrown cause chain. */
            String all() {
                StringBuilder b = new StringBuilder(text);
                for (Throwable t = thrown; t != null; t = t.getCause()) b.append(" | ").append(t);
                return b.toString();
            }
        }

        final List<Line> lines = new CopyOnWriteArrayList<>();
        final Logger store = Logger.getLogger("io.flottila.runtime.store");   // System.Logger delegates to java.util.logging
        final SimpleFormatter formatter = new SimpleFormatter();

        void attach() {
            setLevel(Level.ALL);
            store.addHandler(this);
        }

        void detach() { store.removeHandler(this); }

        @Override
        public void publish(LogRecord r) {
            if (r.getLoggerName() == null || !r.getLoggerName().startsWith("io.flottila.runtime.store")) return;
            Thread t = Thread.currentThread();
            lines.add(new Line(r.getLevel(), formatter.formatMessage(r), t.getName(), t.isVirtual(), r.getThrown()));
        }

        List<Line> at(Level level) {
            return lines.stream().filter(l -> l.level().equals(level)).toList();
        }

        @Override public void flush() {}

        @Override public void close() {}
    }

    /** Whether {@code text} has the number {@code n} on its own, grouped (1,000) or not. */
    static boolean saysNumber(String text, long n) {
        String ungrouped = text.replaceAll("(?<=\\d)[,'\u00a0\u202f](?=\\d{3})", "");
        return Pattern.compile("(?<![\\d.])" + n + "(?![\\d.])").matcher(ungrouped).find();
    }

    // --- the cases

    @Test
    void oneRunDeletesEveryRowOlderThanTheRetention() {
        for (int step = 0; step < 3; step++) {
            Instant created = cutoff.minus(DAY.multipliedBy(3 - step));
            message("m-old", step, created, "invoiceId=4711", "orderNo=o-9");
            body("m-old", step, "body", created, 100 * (step + 1));
        }
        body("m-old", 2, "attachment", cutoff.minus(DAY), 400);
        processed("m-old", cutoff.minus(DAY.multipliedBy(3)));
        message("m-new", 0, cutoff.plus(DAY), "invoiceId=4711");   // the same key value, another message
        body("m-new", 0, cutoff.plus(DAY));
        processed("m-new", cutoff.plus(DAY));
        Retention r = job(1000);

        Retention.Run run = run(r);

        assertEquals(rowsOf("m-new").stream().sorted().toList(), left(), "only the newer message is left, with its key, body and processed id");
        assertEquals("DONE", outcome(run));
        assertEquals(cutoff, run.cutoff(), "the injected clock minus the max age");
        assertEquals(List.of(3L, 2L, 4L, 1000L, 1L, 0L), List.of(run.messages(), run.keys(), run.bodies(), run.bodyBytes(), run.processedIds(), run.kept()),
                "messages, keys, bodies, bodyBytes, processedIds, kept");
        assertEquals(3, run.batches(), "one batch per phase: each scans fewer rows than the batch");
        Retention.Totals t = r.totals();
        assertEquals(List.of(1L, 0L, 0L, 3L, 2L, 4L, 1000L, 1L), List.of((long) t.runs(), (long) t.skipped(), (long) t.failed(), (long) t.messages(),
                (long) t.keys(), (long) t.bodies(), (long) t.bodyBytes(), (long) t.processedIds()),
                "runs, skipped, failed, messages, keys, bodies, bodyBytes, processedIds");
        List<Logs.Line> info = logs.at(Level.INFO);
        assertEquals(1, info.size(), "one INFO line per run: " + logs.lines);
        Logs.Line line = info.get(0);
        assertTrue(line.virtual() && line.thread().equals("flottila-retention"),
                "the run ran on a virtual thread named flottila-retention, not the caller's: " + line.thread() + ", virtual " + line.virtual());
        for (long n : new long[] {3, 2, 4, 1000, 1}) assertTrue(saysNumber(line.text(), n), "the line has the count " + n + ": " + line.text());
        assertEquals(List.of(), logs.at(Level.WARNING), "nothing failed");
    }

    @Test
    void newerRowsStay() {
        message("m-new", 0, cutoff.plusSeconds(1), "invoiceId=1");
        message("m-new", 1, cutoff.plus(DAY), "invoiceId=1");
        body("m-new", 0, cutoff.plusSeconds(1));
        body("m-new", 1, cutoff.plus(DAY));
        processed("m-new", cutoff.plusSeconds(1));
        body("o-new", 0, cutoff.plus(MICRO));        // an orphan body, a microsecond younger than the cutoff
        processed("m-gone", cutoff.plus(MICRO));     // a processed id whose message is gone, a microsecond younger
        message("m-old", 0, cutoff.minus(DAY), "invoiceId=2");   // the witness: the run deletes what is older
        body("m-old", 0, cutoff.minus(DAY));
        processed("m-old", cutoff.minus(DAY));

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout(rowsOf("m-old")), left(), "every row younger than the cutoff stays");
        assertEquals(List.of(1L, 1L, 1L, 1L), List.of(run.messages(), run.keys(), run.bodies(), run.processedIds()), "the witness's rows only");
    }

    @Test
    void aRowExactlyAtTheCutoffStays() {
        message("m-at", 0, cutoff);
        message("m-before", 0, cutoff.minus(MICRO));
        body("o-at", 0, cutoff);
        body("o-before", 0, cutoff.minus(MICRO));
        processed("g-at", cutoff);
        processed("g-before", cutoff.minus(MICRO));

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout("messages m-before@0", "body o-before/0/body", "processed g-before"), left(),
                "older than the cutoff goes, a row at it stays: for messages, bodies and processed ids");
        assertEquals(cutoff, run.cutoff());
        assertEquals(List.of(1L, 1L, 1L), List.of(run.messages(), run.bodies(), run.processedIds()));
        assertEquals(0L, run.kept(), "m-at is not older than the cutoff: the scan never reads it, so it is not a kept row either");
    }

    @Test
    void aMessageStraddlingTheCutoffStaysWhole() {
        message("s-at", 0, cutoff.minus(DAY), "invoiceId=1");
        message("s-at", 1, cutoff, "invoiceId=1");                 // its last step exactly at the cutoff: not older
        body("s-at", 0, cutoff.minus(DAY));
        body("s-at", 1, cutoff);
        processed("s-at", cutoff.minus(DAY));
        message("s-after", 0, cutoff.minus(DAY), "invoiceId=2");
        message("s-after", 1, cutoff.plus(Duration.ofHours(1)), "invoiceId=2");
        body("s-after", 0, cutoff.minus(DAY));
        processed("s-after", cutoff.minus(DAY));
        message("gone", 0, cutoff.minus(DAY), "invoiceId=3");     // the witness: every row older, a microsecond for the last
        message("gone", 1, cutoff.minus(MICRO), "invoiceId=3");
        body("gone", 0, cutoff.minus(DAY));

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout(rowsOf("gone")), left(), "a message with a row at or after the cutoff keeps every row, its key, bodies and processed id");
        assertEquals(2L, run.messages(), "the witness's two rows");
    }

    @Test
    void anOpenWorkItemKeepsItsMessageAndBodies() {
        message("w-open", 0, cutoff.minus(DAY.multipliedBy(2)), "invoiceId=1");
        message("w-open", 1, cutoff.minus(DAY), "invoiceId=1");
        body("w-open", 0, cutoff.minus(DAY.multipliedBy(2)));
        body("w-open", 1, cutoff.minus(DAY));
        processed("w-open", cutoff.minus(DAY.multipliedBy(2)));
        work("w-open", false);
        message("w-held", 0, cutoff.minus(DAY), "invoiceId=2");   // held for a human (D-055): still open
        body("w-held", 0, cutoff.minus(DAY));
        processed("w-held", cutoff.minus(DAY));
        work("w-held", true);
        body("w-body-only", 0, cutoff.minus(DAY));                 // a work item whose ingress row is not there: its body stays
        work("w-body-only", false);
        message("done", 0, cutoff.minus(DAY), "invoiceId=3");      // the witness: no work item
        body("done", 0, cutoff.minus(DAY));
        processed("done", cutoff.minus(DAY));

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout(rowsOf("done")), left(), "a work item, held or not, keeps its message, keys, bodies and processed id");
        assertEquals(List.of(1L, 1L, 1L), List.of(run.messages(), run.bodies(), run.processedIds()));
    }

    @Test
    void anOpenDlqRowKeepsItsMessage() {
        message("p-1", 0, cutoff.minus(DAY.multipliedBy(2)), "invoiceId=1");
        message("p-1", 1, cutoff.minus(DAY), "invoiceId=1");
        body("p-1", 0, cutoff.minus(DAY.multipliedBy(2)));
        body("p-1", 1, cutoff.minus(DAY));
        processed("p-1", cutoff.minus(DAY.multipliedBy(2)));
        dlq("p-1", "p-1/1/body", cutoff.minus(DAY.multipliedBy(30)));   // parked long ago: a DLQ row never ages out
        message("p-2", 0, cutoff.minus(DAY), "invoiceId=2");
        body("p-2", 0, cutoff.minus(DAY));
        dlq("p-2", null, clock.instant());                              // a DLQ row naming no body: its id alone keeps the message
        dlq("lost", "lost/0/body", cutoff.minus(DAY.multipliedBy(60)));  // no message, no body left: the row stays all the same
        message("done", 0, cutoff.minus(DAY), "invoiceId=3");          // the witness: no DLQ row
        body("done", 0, cutoff.minus(DAY));

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout(rowsOf("done")), left(), "a DLQ row keeps its message whole; every DLQ row stays");
        assertEquals(List.of(1L, 1L), List.of(run.messages(), run.bodies()));
    }

    @Test
    void aDlqRowKeepsTheBodyItNamesUnderAnotherMessage() {
        message("orig", 0, cutoff.minus(DAY.multipliedBy(2)), "invoiceId=1");
        message("orig", 1, cutoff.minus(DAY), "invoiceId=1");
        body("orig", 0, cutoff.minus(DAY.multipliedBy(2)));
        body("orig", 1, cutoff.minus(DAY));
        dlq("retry", "orig/0/body", cutoff.minus(DAY));   // a replay parked with the original's step-0 body (Replay, Parking)

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout("messages orig@0", "messages orig@1", "key orig invoiceId=1", "body orig/1/body"), left(),
                "the original goes — no work item, no DLQ row has its id — but the body the DLQ row names stays");
        assertEquals(List.of(2L, 1L, 1L), List.of(run.messages(), run.keys(), run.bodies()));
    }

    @Test
    void processedIdsGoOnlyWithOrAfterTheirMessage() {
        message("with", 0, cutoff.minus(DAY));
        processed("with", cutoff.minus(DAY));             // goes in the run that deletes its message
        processed("after", cutoff.minus(DAY));            // its message went before: goes
        message("straddle", 0, cutoff.minus(DAY));
        message("straddle", 1, cutoff.plus(DAY));
        processed("straddle", cutoff.minus(DAY));         // its message stays: so does it
        message("working", 0, cutoff.minus(DAY));
        work("working", false);
        processed("working", cutoff.minus(DAY));
        message("parked", 0, cutoff.minus(DAY));
        dlq("parked", null, cutoff.minus(DAY));
        processed("parked", cutoff.minus(DAY));
        processed("young", cutoff.plus(DAY));             // its message gone, itself younger than the cutoff: stays

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout("messages with@0", "processed with", "processed after"), left(),
                "a processed id goes with its message or after it, never while its message stays");
        assertEquals(2L, run.processedIds());
    }

    @Test
    void anOrphanBodyOlderThanTheRetentionGoes() {
        body("orphan", 0, "body", cutoff.minus(DAY), 10);
        body("orphan", 0, "invoice.pdf", cutoff.minus(DAY), 20);   // an attachment part: a body row like any other
        body("young", 0, cutoff.plus(DAY));                        // an orphan younger than the cutoff
        message("live", 0, cutoff.plus(Duration.ofHours(1)));
        body("live", 0, cutoff.minus(Duration.ofHours(1)));        // older than the cutoff, its message is not: stays

        Retention.Run run = run(job(1000));

        assertEquals(seededWithout("body orphan/0/body", "body orphan/0/invoice.pdf"), left(),
                "a body no message, work item or DLQ row names goes once it is older than the cutoff");
        assertEquals(List.of(2L, 30L, 0L), List.of(run.bodies(), run.bodyBytes(), run.messages()));
    }

    @Test
    void batchesAreBoundedAndTheRunFinishes() {
        noteDeletes("messages", "bodies", "processed_ids");
        for (int i = 1; i <= 4; i++) {
            Instant created = cutoff.minus(DAY.multipliedBy(5 - i));
            message("m-" + i, 0, created, "invoiceId=" + i);
            body("o-" + i, 0, created);          // orphan bodies
            processed("g-" + i, created);        // processed ids whose messages went before
        }

        Retention.Run run = run(job(2));

        assertEquals(List.of(), left(), "all four messages with their keys, all four bodies, all four processed ids are gone");
        assertEquals("DONE", outcome(run));
        assertEquals(List.of(4L, 4L, 4L, 4L), List.of(run.messages(), run.keys(), run.bodies(), run.processedIds()), "messages, keys, bodies, processedIds");
        List<String> perTransaction = new ArrayList<>();
        for (Row r : db.query("select tbl, count(*) from deleted_in group by tbl, tx order by min(tx)").execute().await()) {
            perTransaction.add(r.getString(0) + " " + r.getLong(1));
        }
        assertEquals(List.of("messages 2", "messages 2", "bodies 2", "bodies 2", "processed_ids 2", "processed_ids 2"), perTransaction,
                "two rows per transaction, one table per transaction, phase after phase: the batch bounds every delete");
        long unlocked = db.query("select count(*) from deleted_in where not advisory").execute().await().iterator().next().getLong(0);
        assertEquals(0L, unlocked, "every deleting transaction held the advisory lock while it deleted");
        assertEquals(9, run.batches(), "3 batches per phase: 2 rows, 2 rows, then 0 rows (fewer than the batch ends the phase)");
    }

    /**
     * The audit table {@code deleted_in}: every row deleted from {@code tables} notes its table, its message's id ({@code id} or
     * {@code message_id}), its transaction, and whether that transaction held an advisory lock.
     */
    void noteDeletes(String... tables) {
        db.query("create table deleted_in (tbl text not null, id text, tx bigint not null, advisory boolean not null)").execute().await();
        db.query("create function note_delete() returns trigger language plpgsql as $$ begin insert into deleted_in values (TG_TABLE_NAME, "
                + "coalesce(to_jsonb(old)->>'id', to_jsonb(old)->>'message_id'), txid_current(), "
                + "exists (select 1 from pg_locks where locktype = 'advisory' and pid = pg_backend_pid() and granted)); return old; end $$").execute().await();
        for (String table : tables) {
            db.query("create trigger note_delete after delete on " + table + " for each row execute function note_delete()").execute().await();
        }
    }

    /** A message goes whole in one batch's transaction, also when its rows and another message's interleave in the scan (review r1). */
    @Test
    void aMessageGoesWholeInOneBatchTransaction() {
        noteDeletes("messages", "message_keys");
        message("a", 0, cutoff.minus(DAY.multipliedBy(4)), "invoiceId=a");   // by created_at: a@0 < b@0 < a@1 < b@1
        message("b", 0, cutoff.minus(DAY.multipliedBy(3)), "invoiceId=b");
        message("a", 1, cutoff.minus(DAY.multipliedBy(2)), "invoiceId=a");
        message("b", 1, cutoff.minus(DAY), "invoiceId=b");

        Retention.Run run = run(job(2));   // the first batch scans a@0 and b@0: a@1 and b@1 lie beyond it

        assertEquals(List.of(), left(), "both messages are gone, with their key rows");
        List<String> perMessage = new ArrayList<>();
        for (Row r : db.query("select id, count(*), count(distinct tx) from deleted_in group by id order by id").execute().await()) {
            perMessage.add(r.getString(0) + ": " + r.getLong(1) + " rows in " + r.getLong(2) + " transaction(s)");
        }
        assertEquals(List.of("a: 3 rows in 1 transaction(s)", "b: 3 rows in 1 transaction(s)"), perMessage,
                "each message's two messages rows and its key row are deleted in one transaction, never split across batches");
        assertEquals("DONE", outcome(run));
        assertEquals(List.of(4L, 2L, 0L), List.of(run.messages(), run.keys(), run.kept()), "messages, keys, kept");
        assertEquals(4, run.batches(), "messages: the batch that scanned a@0 and b@0 took both messages whole, then one empty batch; "
                + "one each for bodies and processed ids");
    }

    @Test
    void keptRowsAtTheHeadDoNotStallTheRun() {
        for (int i = 1; i <= 3; i++) {   // the three oldest rows are kept: more than a batch at the head of the scan
            message("k-" + i, 0, cutoff.minus(DAY.multipliedBy(11 - i)));
            work("k-" + i, false);
        }
        message("d-1", 0, cutoff.minus(DAY.multipliedBy(2)));
        message("d-2", 0, cutoff.minus(DAY));

        Retention.Run run = run(job(2));   // bounded: a cursor that does not pass the kept rows scans them again and again

        assertEquals(seededWithout("messages d-1@0", "messages d-2@0"), left(), "the rows behind the kept head go");
        assertEquals("DONE", outcome(run));
        assertEquals(List.of(2L, 3L), List.of(run.messages(), run.kept()), "messages, kept");
        assertEquals(5, run.batches(), "messages in 3 batches (2 kept; 1 kept and 1 deleted; 1 deleted), then one each for bodies and processed ids");
    }

    /** The bodies and processed ids phases pass kept rows the same way: their cursors move past the head they keep. */
    @Test
    void keptBodiesAndProcessedIdsAtTheHeadDoNotStallTheRun() {
        for (int i = 1; i <= 3; i++) {   // young messages whose body and processed id are old: kept, at the head of both scans
            message("k-" + i, 0, cutoff.plus(DAY));
            body("k-" + i, 0, cutoff.minus(DAY.multipliedBy(11 - i)));
            processed("k-" + i, cutoff.minus(DAY.multipliedBy(11 - i)));
        }
        body("o-1", 0, cutoff.minus(DAY.multipliedBy(2)));
        body("o-2", 0, cutoff.minus(DAY));
        processed("g-1", cutoff.minus(DAY.multipliedBy(2)));
        processed("g-2", cutoff.minus(DAY));

        Retention.Run run = run(job(2));   // bounded, as above

        assertEquals(seededWithout("body o-1/0/body", "body o-2/0/body", "processed g-1", "processed g-2"), left(), "the rows behind each kept head go");
        assertEquals("DONE", outcome(run));
        assertEquals(List.of(0L, 2L, 2L), List.of(run.messages(), run.bodies(), run.processedIds()), "messages, bodies, processedIds");
        assertEquals(7, run.batches(), "one empty messages batch, then 5 rows in batches of 2 for bodies (k-1 k-2 | k-3 o-1 | o-2) "
                + "and the same for processed ids: a cursor that went back would scan more batches");
    }

    @Test
    void bodyBytesCountsBytesNotRows() {
        body("o-1", 0, "body", cutoff.minus(DAY), 1);
        body("o-2", 0, "body", cutoff.minus(DAY), 3000);        // above the toast threshold: stored out of line (V6)
        body("o-3", 0, "body", cutoff.minus(DAY), 200_000);
        body("o-4", 0, "body", cutoff.plus(DAY), 10);           // younger: neither deleted nor counted

        Retention r = job(1000);
        Retention.Run run = run(r);

        assertEquals(seededWithout("body o-1/0/body", "body o-2/0/body", "body o-3/0/body"), left());
        assertEquals(3L, run.bodies(), "rows");
        assertEquals(203_001L, run.bodyBytes(), "the deleted bodies' bytes: 1 + 3000 + 200000");
        assertEquals(List.of(3L, 203_001L), List.of((long) r.totals().bodies(), (long) r.totals().bodyBytes()), "the totals count the same: bodies, bodyBytes");
    }

    @Test
    void anotherSessionHoldingTheLockSkipsTheRun() {
        message("m-1", 0, cutoff.minus(DAY), "invoiceId=1");
        Held messages = lock("messages");
        Retention first = job(1000);
        Future<Retention.Run> inFlight = first.runOnce();
        until(() -> waiting("relation") == 1, "the first job's batch took the advisory lock and waits on the locked messages table");
        Retention second = job(newPool(), 1000);   // another node: a pool of its own

        Retention.Run skipped = run(second);   // bounded: a blocking lock would wait for the first job's transaction

        assertEquals("SKIPPED", outcome(skipped), "the lock is taken: the run is skipped");
        assertEquals(cutoff, skipped.cutoff());
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), List.of(skipped.messages(), skipped.keys(), skipped.bodies(), skipped.bodyBytes(), skipped.processedIds()));
        assertEquals(List.of(1L, 1L, 0L), List.of((long) second.totals().runs(), (long) second.totals().skipped(), (long) second.totals().failed()),
                "runs, skipped, failed");
        assertEquals(1, waiting("relation"), "the skipped run left no statement waiting on the table: only the first job's batch waits");
        assertFalse(inFlight.isComplete(), "the first job's run is still in its batch");
        messages.release();
        Retention.Run done = await(inFlight, "the first job's run");
        assertEquals("DONE", outcome(done));
        assertEquals(1L, done.messages());
        assertEquals(seededWithout(rowsOf("m-1")), left());
    }

    @Test
    void aLockTakenBetweenBatchesEndsTheRunEarly() {
        message("m-1", 0, cutoff.minus(DAY));          // phase 1
        body("orphan", 0, cutoff.minus(DAY));          // phase 2
        processed("after", cutoff.minus(DAY));         // phase 3: its message went before
        Held bodies = lock("bodies");
        Retention r = job(1000);
        Future<Retention.Run> running = r.runOnce();
        until(() -> waiting("relation") == 1, "the bodies batch took the advisory lock and waits on the locked bodies table");
        Row held = observer.preparedQuery("select l.classid::bigint, l.objid::bigint, l.objsubid from pg_locks l join pg_stat_activity a on a.pid = l.pid "
                + "where a.datname = $1 and a.wait_event_type = 'Lock' and l.locktype = 'advisory' and l.granted").execute(Tuple.of(database)).await().iterator().next();
        assertEquals(1, held.getInteger(2), "fixture: the lock is taken by one bigint key (objsubid 1)");
        long key = (held.getLong(0) << 32) | held.getLong(1);
        PgConnection rival = PgConnection.connect(vertx, options(database)).await();
        sessions.add(rival);
        Future<RowSet<Row>> taken = rival.preparedQuery("select pg_advisory_lock($1)").execute(Tuple.of(key));
        until(() -> waiting("advisory") == 1, "another session queues for the lock the bodies batch holds");
        bodies.release();
        await(taken, "the other session's lock");   // granted when the bodies batch committed

        Retention.Run early = await(running, "the run");

        assertEquals("SKIPPED", outcome(early), "the next batch found the lock taken by the other session: the run ends there");
        assertEquals(List.of(1L, 1L, 0L), List.of(early.messages(), early.bodies(), early.processedIds()), "what the committed batches deleted");
        assertEquals(List.of("processed after"), left(), "the processed id waits for the next run");
        rival.query("select pg_advisory_unlock_all()").execute().await();
        Retention.Run next = run(r);
        assertEquals("DONE", outcome(next));
        assertEquals(1L, next.processedIds());
        assertEquals(List.of(), left());
    }

    @Test
    void theNextRunIsScheduledOnlyAfterARunEnds() {
        message("m-1", 0, cutoff.minus(DAY));
        Held messages = lock("messages");
        Retention r = job(1000);

        start(r);
        clock.advance(Duration.ZERO);   // a first run scheduled with no delay fires now; one start() began itself is running already
        until(() -> waiting("relation") == 1, "the first run starts at once: its batch waits on the locked messages table");

        assertEquals(0, timer.pending(), "nothing is scheduled while the run is in flight");
        clock.advance(INTERVAL);
        assertEquals(0, timer.pending(), "an interval later still nothing: the next run waits for this one to end");
        messages.release();
        until(() -> r.totals().runs() == 1 && timer.pending() == 1, "the run ends, then the next one is scheduled");
        assertEquals(INTERVAL, timer.nextDelay(), "one interval after the run ended (the clock stood still since)");
        assertEquals(seededWithout(rowsOf("m-1")), left());

        message("m-2", 0, clock.instant().minus(MAX_AGE).minus(DAY));
        clock.advance(INTERVAL.minusNanos(1));
        assertEquals(1, timer.pending(), "not before the interval is over");
        String advancing = Thread.currentThread().getName();
        clock.advance(Duration.ofNanos(1));   // the timer's task runs here, on this thread: it only starts the run
        until(() -> r.totals().runs() == 2 && timer.pending() == 1 && logs.at(Level.INFO).size() == 2,
                "the timer started the second run; it ended, logged and scheduled the third");

        assertEquals(seededWithout(concat(rowsOf("m-1"), rowsOf("m-2"))), left());
        assertEquals(2L, r.totals().messages(), "the totals sum both runs");
        assertEquals(INTERVAL, timer.nextDelay());
        Logs.Line second = logs.at(Level.INFO).get(1);
        assertTrue(second.virtual() && second.thread().equals("flottila-retention"), "the timer's task, run on " + advancing
                + ", started the run on a virtual thread flottila-retention and did not run it itself: " + second.thread() + ", virtual " + second.virtual());
    }

    @Test
    void closeCancelsAndWaitsForTheBatch() {
        message("m-1", 0, cutoff.minus(DAY));
        Held messages = lock("messages");
        Retention r = job(1000);
        start(r);
        clock.advance(Duration.ZERO);
        until(() -> waiting("relation") == 1, "the first run's batch waits on the locked messages table");

        Future<Void> closed = r.close();

        assertEquals(1, waiting("relation"), "fixture: the batch still waits");
        assertFalse(closed.isComplete(), "close waits for the batch in flight: " + closed);
        messages.release();
        await(closed, "close");
        assertEquals(seededWithout(rowsOf("m-1")), left(), "the batch in flight ran to its commit: close let it finish");
        assertEquals(0, timer.pending(), "and nothing is scheduled after close");
    }

    @Test
    void closeBetweenRunsCancelsTheNextRun() {
        Retention r = job(1000);
        start(r);
        clock.advance(Duration.ZERO);
        until(() -> r.totals().runs() == 1 && timer.pending() == 1, "the first run ended and scheduled the next");

        await(r.close(), "close");

        assertEquals(0, timer.pending(), "close cancelled the scheduled run");
        assertEquals(1L, r.totals().runs());
    }

    @Test
    void closeDuringARunEndsItSkippedAfterTheBatchInFlight() {
        message("m-1", 0, cutoff.minus(DAY));    // phase 1: its batch is in flight when close comes
        body("orphan", 0, cutoff.minus(DAY));    // phase 2
        processed("after", cutoff.minus(DAY));   // phase 3: its message went before
        Held messages = lock("messages");
        Retention r = job(1000);
        Future<Retention.Run> running = r.runOnce();
        until(() -> waiting("relation") == 1, "the run's first batch waits on the locked messages table");

        Future<Void> closed = r.close();

        assertFalse(closed.isComplete(), "fixture: close waits for the batch in flight: " + closed);
        messages.release();
        await(closed, "close");
        Retention.Run run = await(running, "the run");
        assertEquals("SKIPPED", outcome(run), "closed while a batch ran: the run ends after that batch and begins no other");
        assertEquals(List.of(1L, 0L, 0L, 1), List.of(run.messages(), run.bodies(), run.processedIds(), run.batches()),
                "messages, bodies, processedIds, batches");
        assertEquals(seededWithout(rowsOf("m-1")), left(), "the batch in flight committed; the orphan body and the processed id stay");
        assertEquals(List.of(1L, 1L, 0L), List.of(r.totals().runs(), r.totals().skipped(), r.totals().failed()), "runs, skipped, failed");
    }

    @Test
    void aClosedJobRefusesToRunAndToStart() {
        message("m-1", 0, cutoff.minus(DAY));
        Retention r = job(1000);
        await(r.close(), "close");

        Future<Retention.Run> refused = r.runOnce();

        assertTrue(refused.failed() && refused.cause() instanceof IllegalStateException,
                "runOnce after close fails at once with IllegalStateException: " + refused);
        assertThrows(IllegalStateException.class, r::start, "start after close");
        assertEquals(0, timer.pending(), "nothing is scheduled");
        assertEquals(0L, r.totals().runs(), "no run ended");
        assertEquals(seededWithout(), left());
    }

    @Test
    void aJobStartsOnlyOnce() {
        Retention r = job(1000);
        start(r);

        assertThrows(IllegalStateException.class, r::start, "a second start");

        clock.advance(Duration.ZERO);
        until(() -> r.totals().runs() == 1 && timer.pending() == 1, "the first start's run ended and scheduled the next one");
        assertEquals(INTERVAL, timer.nextDelay());
    }

    @Test
    void aBatchThatFindsTheLockTakenRollsBack() {
        message("m-1", 0, cutoff.minus(DAY));
        db.query("alter database \"" + database + "\" set log_statement = 'all'").execute().await();   // the sessions opened from here on
        PgConnection rival = PgConnection.connect(vertx, options(database)).await();
        sessions.add(rival);
        rival.preparedQuery("select pg_advisory_lock($1)").execute(Tuple.of(Retention.LOCK_KEY)).await();
        Retention r = job(newPool(), 1000);   // a pool opened after the setting: its sessions log their statements

        Retention.Run run = run(r);

        assertEquals("SKIPPED", outcome(run), "fixture: another session holds the lock");
        assertEquals(1, run.batches(), "the one batch that tried the lock");
        assertEquals(seededWithout(), left());
        until(() -> afterTheLockTry(pg.getLogs()) != null, "the server logged the statement after the batch's lock try");
        assertEquals("ROLLBACK", afterTheLockTry(pg.getLogs()), "the batch's transaction ends rolled back, never committed");
    }

    /**
     * In the server's log ({@code log_line_prefix} {@code '%m [%p] '}): the first statement the session that last tried the
     * retention lock ran after that try, or null while none is logged.
     */
    static String afterTheLockTry(String serverLog) {
        List<String> lines = serverLog.lines().toList();
        int at = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("pg_try_advisory_xact_lock")) at = i;
        if (at < 0) return null;
        java.util.regex.Matcher pid = Pattern.compile("\\[(\\d+)]").matcher(lines.get(at));
        assertTrue(pid.find(), "fixture: the server log names the session: " + lines.get(at));
        String session = "[" + pid.group(1) + "]";
        for (String line : lines.subList(at + 1, lines.size())) {
            if (!line.contains(session)) continue;
            int s = line.indexOf("statement: ");
            if (s >= 0) return line.substring(s + "statement: ".length()).strip();
            int e = line.indexOf("execute ");
            if (e >= 0) return line.substring(e).strip();
        }
        return null;
    }

    @Test
    void aTimerThatRefusesTheNextRunIsAWarningAndTheRunStillCounts() {
        message("m-1", 0, cutoff.minus(DAY));
        IllegalStateException refusal = new IllegalStateException("the fixture's timer refuses every schedule");
        AtomicInteger asked = new AtomicInteger();
        io.flottila.api.Timer refusing = (delay, task) -> {
            asked.incrementAndGet();
            throw refusal;
        };
        Retention r = new Retention(pool, clock, refusing, new Retention.Config(MAX_AGE, INTERVAL, 1000));
        jobs.add(r);

        start(r);   // the first run begins at once; scheduling the next one is refused

        until(() -> r.totals().runs() == 1 && !logs.at(Level.WARNING).isEmpty(), "the run ended and the refused schedule was logged");
        List<Logs.Line> warnings = logs.at(Level.WARNING);
        assertEquals(1, warnings.size(), "one WARNING: " + logs.lines);
        assertTrue(warnings.get(0).thrown() == refusal && warnings.get(0).text().contains("timer"),
                "it names the timer and carries its refusal as the cause: " + warnings.get(0).all());
        assertEquals(1, asked.get(), "the next run was asked for once");
        assertEquals(List.of(1L, 0L, 0L, 1L), List.of(r.totals().runs(), r.totals().skipped(), r.totals().failed(), r.totals().messages()),
                "the run itself ended DONE and counted: runs, skipped, failed, messages");
        assertEquals(seededWithout(rowsOf("m-1")), left());
    }

    @Test
    void aFailedBatchIsCountedAndTheNextRunComes() {
        message("m-1", 0, cutoff.minus(DAY));
        db.query("create function refuse_delete() returns trigger language plpgsql as $$ begin "
                + "raise exception 'the fixture refuses deletes on messages'; end $$").execute().await();
        db.query("create trigger refuse_delete before delete on messages for each statement execute function refuse_delete()").execute().await();
        Retention once = job(1000);

        Retention.Run failed = run(once);

        assertEquals("FAILED", outcome(failed), "the batch failed: so did the run (its future completes, with the outcome)");
        assertEquals(cutoff, failed.cutoff());
        assertEquals(List.of(1L, 1L, 0L), List.of((long) once.totals().runs(), (long) once.totals().failed(), (long) once.totals().skipped()),
                "runs, failed, skipped");
        List<Logs.Line> warnings = logs.at(Level.WARNING);
        assertEquals(1, warnings.size(), "one WARNING for the failed batch: " + logs.lines);
        assertTrue(warnings.get(0).all().contains("the fixture refuses deletes on messages"), "it carries the cause: " + warnings.get(0).all());
        assertEquals(seededWithout(), left(), "the failed batch rolled back");

        FakeTimer own = new FakeTimer(clock);   // the scheduled job's timer alone: what it has pending is what it scheduled
        Retention scheduled = new Retention(pool, clock, own, new Retention.Config(MAX_AGE, INTERVAL, 1000));
        jobs.add(scheduled);
        start(scheduled);
        clock.advance(Duration.ZERO);
        until(() -> scheduled.totals().runs() == 1 && own.pending() == 1, "the scheduled run fails too, and the next one is scheduled all the same");
        assertEquals(1L, scheduled.totals().failed());
        assertEquals(INTERVAL, own.nextDelay(), "on schedule: an interval after the failed run");
        db.query("drop trigger refuse_delete on messages").execute().await();
        clock.advance(INTERVAL);
        until(() -> scheduled.totals().runs() == 2 && own.pending() == 1, "the next run comes, ends and schedules its own successor");

        assertEquals(seededWithout(rowsOf("m-1")), left(), "the next run deleted what the failed ones could not");
        assertEquals(List.of(1L, 1L), List.of((long) scheduled.totals().failed(), (long) scheduled.totals().messages()), "failed, messages");
        assertEquals(2, logs.at(Level.WARNING).size(), "one WARNING per failed run: " + logs.lines);
    }

    @Test
    void theCutoffComesFromTheInjectedClock() {
        for (int i = 1; i <= 4; i++) message("m-" + i, 0, cutoff.minus(DAY.multipliedBy(5 - i)));   // ~390 days after the server's now()
        message("m-mid", 0, cutoff.plus(DAY));   // younger than this run's cutoff, older than the next run's
        Held messages = lock("messages");
        Retention r = job(2);
        Future<Retention.Run> running = r.runOnce();
        until(() -> waiting("relation") == 1, "the run's first batch waits on the locked messages table");
        clock.advance(Duration.ofDays(10));   // the clock moves on while the run is in its first batch
        messages.release();

        Retention.Run first = await(running, "the run");

        assertEquals(cutoff, first.cutoff(), "the clock at the run's start minus the max age");
        assertEquals(4L, first.messages(), "the rows older than it, though every one is younger than the server's now() minus the max age");
        assertEquals(seededWithout(concat(rowsOf("m-1"), rowsOf("m-2"), rowsOf("m-3"), rowsOf("m-4"))), left(),
                "m-mid stays: the later batches kept the run's cutoff although the clock had moved");
        Retention.Run second = run(r);
        assertEquals(cutoff.plus(Duration.ofDays(10)), second.cutoff(), "the next run takes the clock again");
        assertEquals(1L, second.messages());
        assertEquals(List.of(), left());
    }
}
