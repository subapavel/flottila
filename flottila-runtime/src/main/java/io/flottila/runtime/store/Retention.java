package io.flottila.runtime.store;

import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The retention job (D-088, F-20): deletes what is older than the max age, in keyset batches, in the background. A run fixes its
 * cutoff once — the injected clock minus the max age, never the server's {@code now()} — and runs three phases, each in batches of
 * at most {@link Config#batch} scanned rows, one transaction per batch whose first statement tries the advisory lock
 * {@link #LOCK_KEY} without waiting:
 * <ol>
 *   <li>{@code messages} and {@code message_keys}: a message with a scanned row older than the cutoff goes whole — every row of it,
 *       in that batch's transaction, those beyond the scan included — when no row of it is at or after the cutoff and no work row
 *       (held or not) and no DLQ row has its id; the key rows of the deleted rows' {@code keys} go with them. A scanned row whose
 *       message stays is passed by the cursor and counted in {@link Run#kept}.</li>
 *   <li>{@code bodies}: a body older than the cutoff goes when no messages row and no work row has its message's id and no DLQ row's
 *       {@code body_key} names it (orphans included); its bytes are counted.</li>
 *   <li>{@code processed_ids}: a row older than the cutoff goes when no messages row has its message's id.</li>
 * </ol>
 * A phase ends with the batch that scanned fewer rows than the batch size. The lock found taken ends the run {@link Outcome#SKIPPED}
 * with what the committed batches deleted; so does {@link #close} between two batches. A failed batch is rolled back (unless its
 * commit is what failed), logged as a WARNING with its cause and ends the run {@link Outcome#FAILED}. Every run logs one INFO line with its counts. DLQ rows are never
 * deleted. Correctness never depends on the lock: it only keeps two nodes from doing the same work at once.
 *
 * <p>{@link #start} begins the first run at once and schedules each next one {@link Config#interval} after a run ended, so runs
 * of the schedule never overlap; every run runs on a virtual thread named {@code flottila-retention}, the timer's task only starts
 * it. {@link #runOnce} starts one run outside the schedule.
 *
 * @since 0.1
 */
public final class Retention {

    private static final System.Logger LOG = System.getLogger(Retention.class.getName());

    /** The advisory lock every batch tries first: the bytes of "flottila" (D-088). */
    static final long LOCK_KEY = 0x666c6f7474696c61L;

    private static final String LOCK = "select pg_try_advisory_xact_lock($1)";

    /** The most rows a batch scans (D-088): a batch is one transaction holding its row locks. */
    static final int MAX_BATCH = 10_000;

    /**
     * What the job is given: how old a row may get, how long after a run ended the next one starts, and how many rows a batch scans.
     *
     * @param maxAge more than zero
     * @param interval more than zero
     * @param batch 1 to 10000 rows
     * @since 0.1
     */
    public record Config(Duration maxAge, Duration interval, int batch) {
        /** Refuses a missing or non-positive duration and a batch outside 1 to 10000 rows: nothing unlimited, nothing absent. */
        public Config {
            Objects.requireNonNull(maxAge, "maxAge");
            Objects.requireNonNull(interval, "interval");
            if (!maxAge.isPositive()) throw new IllegalArgumentException("the max age must be more than zero, was " + maxAge);
            if (!interval.isPositive()) throw new IllegalArgumentException("the interval must be more than zero, was " + interval);
            if (batch < 1 || batch > MAX_BATCH) throw new IllegalArgumentException("a batch must be 1 to " + MAX_BATCH + " rows, was " + batch);
        }
    }

    /** How a run ended. */
    public enum Outcome {
        /** Every phase ran to its last, short batch. */
        DONE,
        /** The lock was taken by another session, or the job closed: the run ended after its last committed batch. */
        SKIPPED,
        /** A batch failed and was rolled back: the run ended there. */
        FAILED
    }

    /**
     * One run.
     *
     * @param cutoff the clock at the run's start minus the max age: rows older than it are deleted
     * @param messages messages rows deleted
     * @param keys message_keys rows deleted
     * @param bodies bodies rows deleted
     * @param bodyBytes the bytes of the deleted bodies
     * @param processedIds processed_ids rows deleted
     * @param kept messages rows older than the cutoff the run kept (a newer row of the message, a work or a DLQ row)
     * @param batches transactions the run began, the last, short one of each phase included
     * @since 0.1
     */
    public record Run(Instant cutoff, Outcome outcome, long messages, long keys, long bodies, long bodyBytes, long processedIds, long kept,
                      int batches) {}

    /**
     * The sums over every run that ended since the job was built.
     *
     * @param runs the runs that ended
     * @param skipped the runs that ended {@link Outcome#SKIPPED}
     * @param failed the runs that ended {@link Outcome#FAILED}
     * @since 0.1
     */
    public record Totals(long runs, long skipped, long failed, long messages, long keys, long bodies, long bodyBytes, long processedIds) {
        private Totals plus(Run r) {
            return new Totals(runs + 1, skipped + (r.outcome() == Outcome.SKIPPED ? 1 : 0), failed + (r.outcome() == Outcome.FAILED ? 1 : 0),
                    messages + r.messages(), keys + r.keys(), bodies + r.bodies(), bodyBytes + r.bodyBytes(), processedIds + r.processedIds());
        }
    }

    /**
     * One table's keyset scan: its statement ({@code $1} cutoff, {@code $2} batch, {@code $3..} the cursor; answering rows scanned,
     * rows deleted, the extra count, rows kept, then the cursor) and its first cursor.
     */
    private enum Phase {
        MESSAGES("""
                with scan as (
                    select id, step, created_at from messages
                     where created_at < $1 and (created_at, id, step) > (coalesce($3, '-infinity'::timestamptz), $4, $5)
                     order by created_at, id, step limit $2),
                gone as (
                    delete from messages m using (select distinct id from scan) s
                     where m.id = s.id
                       and not exists (select 1 from messages y where y.id = s.id and y.created_at >= $1)
                       and not exists (select 1 from work w where w.id = s.id)
                       and not exists (select 1 from dlq d where d.id = s.id)
                     returning m.id, m.keys),
                keys as (
                    delete from message_keys k using (select distinct g.id, e.key, e.value from gone g, jsonb_each_text(g.keys) e) x
                     where k.name = x.key and k.value = x.value and k.message_id = x.id
                     returning 1)
                select (select count(*) from scan), (select count(*) from gone), (select count(*) from keys),
                       (select count(*) from scan s where not exists (select 1 from gone g where g.id = s.id)), last.created_at, last.id, last.step
                  from (select 1) one left join lateral
                       (select created_at, id, step from scan order by created_at desc, id desc, step desc limit 1) last on true""",
                "", Integer.MIN_VALUE),
        BODIES("""
                with scan as (
                    select message_id, step, part, created_at from bodies
                     where created_at < $1 and (created_at, message_id, step, part) > (coalesce($3, '-infinity'::timestamptz), $4, $5, $6)
                     order by created_at, message_id, step, part limit $2),
                gone as (
                    delete from bodies b using scan s
                     where b.message_id = s.message_id and b.step = s.step and b.part = s.part
                       and not exists (select 1 from messages m where m.id = s.message_id)
                       and not exists (select 1 from work w where w.id = s.message_id)
                       and not exists (select 1 from dlq d where d.body_key = s.message_id || '/' || s.step || '/' || s.part)
                     returning octet_length(b.bytes) as size)
                select (select count(*) from scan), (select count(*) from gone), (select coalesce(sum(size), 0) from gone), 0::bigint,
                       last.created_at, last.message_id, last.step, last.part
                  from (select 1) one left join lateral
                       (select created_at, message_id, step, part from scan order by created_at desc, message_id desc, step desc, part desc limit 1) last on true""",
                "", Integer.MIN_VALUE, ""),
        PROCESSED_IDS("""
                with scan as (
                    select scope, key_hash, created_at from processed_ids
                     where created_at < $1 and (created_at, scope, key_hash) > (coalesce($3, '-infinity'::timestamptz), $4, $5)
                     order by created_at, scope, key_hash limit $2),
                gone as (
                    delete from processed_ids p using scan s
                     where p.scope = s.scope and p.key_hash = s.key_hash
                       and not exists (select 1 from messages m where m.id = p.message_id)
                     returning 1)
                select (select count(*) from scan), (select count(*) from gone), 0::bigint, 0::bigint, last.created_at, last.scope, last.key_hash
                  from (select 1) one left join lateral
                       (select created_at, scope, key_hash from scan order by created_at desc, scope desc, key_hash desc limit 1) last on true""",
                "", Buffer.buffer());

        final String sql;
        /** The cursor before the first row: no created_at (read as -infinity in SQL), then the smallest value of each key column. */
        final List<Object> start;

        Phase(String sql, Object... keyStart) {
            this.sql = sql;
            List<Object> s = new ArrayList<>();
            s.add(null);
            s.addAll(List.of(keyStart));
            this.start = s;
        }
    }

    /** What one batch scanned, deleted and kept, and the cursor after its last scanned row. */
    private record Batch(long scanned, long deleted, long extra, long kept, List<Object> cursor) {
        static Batch of(Row r, int cursorColumns) {
            List<Object> cursor = new ArrayList<>();
            for (int i = 0; i < cursorColumns; i++) cursor.add(r.getValue(4 + i));
            return new Batch(r.getLong(0), r.getLong(1), r.getLong(2), r.getLong(3), cursor);
        }
    }

    /** The lock found taken: the batch's transaction is failed with it so that it rolls back (D-088). */
    private static final class Taken extends RuntimeException {
        private Taken() {
            super("the retention lock is taken", null, false, false);
        }
    }

    private final Pool pool;
    private final Clock clock;
    private final Timer timer;
    private final Config config;

    // guarded by this
    private boolean started;
    private volatile boolean closed;
    private Timer.Cancellable next;
    private final Set<Future<Run>> inFlight = new HashSet<>();
    private Totals totals = new Totals(0, 0, 0, 0, 0, 0, 0, 0);

    /** A job over {@code pool}: nothing runs before {@link #start} or {@link #runOnce}. */
    public Retention(Pool pool, Clock clock, Timer timer, Config config) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Begins the first run at once, on a virtual thread, and returns; each run of the schedule schedules the next one an interval
     * after it ended.
     *
     * @throws IllegalStateException started already, or closed
     */
    public synchronized void start() {
        if (started || closed) throw new IllegalStateException("the retention job can start once, before it closed");
        started = true;
        begin(true);
    }

    /**
     * Starts one run on a virtual thread, outside the schedule; the future completes with the run, whatever its outcome. After
     * {@link #close} it fails with {@link IllegalStateException}.
     */
    public Future<Run> runOnce() {
        return begin(false);
    }

    /** The sums over the runs that ended so far. */
    public synchronized Totals totals() {
        return totals;
    }

    /**
     * Cancels the next run of the schedule and lets every run in flight end after its current batch; completes once they ended.
     * Starts nothing any more.
     */
    public Future<Void> close() {
        List<Future<Run>> running;
        synchronized (this) {
            closed = true;
            if (next != null) next.cancel();
            next = null;
            running = List.copyOf(inFlight);
        }
        return Future.join(running).transform(ended -> Future.succeededFuture());
    }

    /** The timer's task: starts the scheduled run, never runs it on the timer's thread. */
    private void scheduled() {
        begin(true);
    }

    private Future<Run> begin(boolean thenSchedule) {
        Promise<Run> done = Promise.promise();
        synchronized (this) {
            if (closed) return Future.failedFuture(new IllegalStateException("the retention job is closed"));
            inFlight.add(done.future());
        }
        Thread.ofVirtual().name("flottila-retention").start(() -> {
            try {
                done.complete(once());
            } finally {
                done.tryFail("the retention run ended without an outcome");   // nothing once completed: an Error escaped once()
                ended(done.future(), thenSchedule);
            }
        });
        return done.future();
    }

    /**
     * A run ended: it is no longer in flight, and a run of the schedule schedules the next one unless the job closed; a timer that
     * refuses the next run is logged as a WARNING — no run follows until the node restarts.
     */
    private synchronized void ended(Future<Run> run, boolean thenSchedule) {
        inFlight.remove(run);
        if (!thenSchedule || closed) return;
        try {
            next = timer.schedule(config.interval(), this::scheduled);
        } catch (RuntimeException refused) {
            LOG.log(System.Logger.Level.WARNING, "the timer refused the next retention run: no run follows until the node restarts", refused);
        }
    }

    /** One run, on the caller's (virtual) thread: its outcome, never a throw for a failed batch. */
    private Run once() {
        Instant cutoff = clock.instant().minus(config.maxAge());
        Tally tally = new Tally();
        Outcome outcome;
        try {
            outcome = phases(cutoff, tally);
        } catch (Exception failed) {   // a failed batch: withTransaction rolled it back, unless its commit is what failed
            LOG.log(System.Logger.Level.WARNING, "a retention batch failed; the run ends FAILED, the next comes on schedule (cutoff "
                    + cutoff + ")", failed);
            outcome = Outcome.FAILED;
        }
        Run run = tally.run(cutoff, outcome);
        synchronized (this) {
            totals = totals.plus(run);
        }
        LOG.log(System.Logger.Level.INFO, "retention run " + outcome + " (cutoff " + cutoff + "): deleted " + run.messages() + " messages rows, "
                + run.keys() + " key rows, " + run.bodies() + " bodies of " + run.bodyBytes() + " bytes, " + run.processedIds()
                + " processed ids; kept " + run.kept() + " messages rows; " + run.batches() + " batches");
        return run;
    }

    /** The three phases in order; DONE, or SKIPPED when the lock was taken or the job closed between two batches. */
    private Outcome phases(Instant cutoff, Tally tally) {
        OffsetDateTime before = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        for (Phase phase : Phase.values()) {
            List<Object> cursor = phase.start;
            while (true) {
                if (closed) return Outcome.SKIPPED;
                tally.batches++;
                Batch b;
                try {
                    b = batch(phase, before, cursor);
                } catch (Taken taken) {
                    return Outcome.SKIPPED;
                }
                tally.add(phase, b);
                if (b.scanned() < config.batch()) break;
                cursor = b.cursor();
            }
        }
        return Outcome.DONE;
    }

    /** One batch of {@code phase} after {@code cursor}: one transaction, the lock tried first, rolled back when it is taken. */
    private Batch batch(Phase phase, OffsetDateTime cutoff, List<Object> cursor) {
        Tuple params = Tuple.tuple().addValue(cutoff).addValue((long) config.batch());
        cursor.forEach(params::addValue);
        return pool.withTransaction((SqlConnection tx) -> tx.preparedQuery(LOCK).execute(Tuple.of(LOCK_KEY))
                .compose(lock -> lock.iterator().next().getBoolean(0)
                        ? tx.preparedQuery(phase.sql).execute(params).map(rows -> Batch.of(rows.iterator().next(), cursor.size()))
                        : Future.failedFuture(new Taken())))
                .await();
    }

    /** A run's counts while it runs, per phase (by ordinal): rows scanned, deleted and kept, and the phase's extra count. */
    private static final class Tally {
        final long[] deleted = new long[Phase.values().length];
        /** Key rows deleted with the messages, the bodies' bytes; none for processed ids. */
        final long[] extra = new long[Phase.values().length];
        /** Scanned rows whose message stayed: messages only. */
        final long[] kept = new long[Phase.values().length];
        int batches;

        void add(Phase phase, Batch b) {
            deleted[phase.ordinal()] += b.deleted();
            extra[phase.ordinal()] += b.extra();
            kept[phase.ordinal()] += b.kept();
        }

        Run run(Instant cutoff, Outcome outcome) {
            int m = Phase.MESSAGES.ordinal();
            int b = Phase.BODIES.ordinal();
            return new Run(cutoff, outcome, deleted[m], extra[m], deleted[b], extra[b], deleted[Phase.PROCESSED_IDS.ordinal()],
                    kept[m], batches);
        }
    }
}
