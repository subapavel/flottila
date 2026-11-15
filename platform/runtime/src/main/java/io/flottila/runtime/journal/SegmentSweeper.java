package io.flottila.runtime.journal;

import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The segment sweeper of a node's journal (CORE-3 items 10 and 14, D-105): a pass removes the segments of this node no row pins.
 *
 * <p>A pass runs on a virtual thread of its own. Its candidates are the journal's segments sealed at least
 * {@code FLOTTILA_JOURNAL_SWEEP_GRACE} ago on the injected clock (by the trailer's sealed ms; a segment found at start counts from
 * the start). It reads the open-save set next — a segment one of those saves names is no candidate — and only then asks the rows
 * ({@link SegmentRows#sweep}), whose pin query runs in a later snapshot: a save that commits after the set was read shows there
 * as the pin it is. Its kept refs pin while younger than {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE} on the injected clock. Of the
 * segments whose row the rows deleted, the journal drops each one's cached read channel and unlinks its file
 * ({@link Journal#swept}), on the pass's thread, before the pass completes: off the hot path, and the journal's bytes are lower
 * once it did. An unlink that fails is a WARNING for that segment alone: the pass goes on, the segment's bytes still count, and
 * the next pass unlinks it first. No candidate left: the pass asks no rows (no transaction).
 *
 * <p>{@link #start} schedules the first pass {@code FLOTTILA_JOURNAL_SWEEP_INTERVAL} later on the injected timer; each pass so
 * scheduled schedules the next one an interval after it ended, a failed pass too (logged as a WARNING). {@link #sweep} runs one
 * pass now. {@link #close} stops the schedule and waits for a pass in flight; a pass is refused afterwards.
 *
 * @since 0.1
 */
public final class SegmentSweeper implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SegmentSweeper.class.getName());

    private final Journal journal;
    private final SegmentRows rows;
    private final Supplier<? extends Collection<JournalRef>> openSaves;
    private final SweepConfig config;
    private final Clock clock;
    private final Timer timer;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    // guarded by lock
    private int running;
    private boolean closed;
    private Timer.Cancellable next;
    private final Set<Long> unlinking = ConcurrentHashMap.newKeySet();   // swept segments whose unlink failed: tried again next pass

    /**
     * @param openSaves the refs of the saves open now ({@code PgIntake.openSaves}), read once per pass
     */
    public SegmentSweeper(Journal journal, SegmentRows rows, Supplier<? extends Collection<JournalRef>> openSaves, SweepConfig config,
                          Clock clock, Timer timer) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.openSaves = Objects.requireNonNull(openSaves, "openSaves");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** Schedules the first pass an interval from now; each scheduled pass schedules the next one. */
    public void start() {
        schedule();
    }

    /** One pass now, on a virtual thread: the segments whose row it deleted (their files unlinked); failed once closed. */
    public Future<Set<Long>> sweep() {
        if (!enter()) return Future.failedFuture(new IllegalStateException("the segment sweeper of node " + journal.node() + " is closed"));
        Promise<Set<Long>> done = Promise.promise();
        Thread.ofVirtual().name("journal-sweeper-" + journal.node()).start(() -> {
            try {
                done.complete(pass());
            } catch (Throwable t) {   // a failed pass (its rows, its unlink): the caller's future fails with it
                done.fail(t);
            } finally {
                leave();
            }
        });
        return done.future();
    }

    private void schedule() {
        lock.lock();
        try {
            if (!closed) next = timer.schedule(config.interval(), this::scheduled);
        } finally {
            lock.unlock();
        }
    }

    /** The timer's pass: started on a virtual thread (the timer's thread only starts it), the next one scheduled when it ended. */
    private void scheduled() {
        sweep().onComplete(ended -> {   // closed after the timer fired: failed at once, and no next one is scheduled
            if (ended.failed()) {
                LOG.log(System.Logger.Level.WARNING, "a sweep of journal segments of node " + journal.node() + " failed; the next one runs "
                        + config.interval() + " later", ended.cause());
            }
            schedule();
        });
    }

    private Set<Long> pass() {
        for (Long n : List.copyOf(unlinking)) {   // an unlink an earlier pass failed: tried again first
            if (unlinked(n, () -> journal.unlink(n))) unlinking.remove(n);
        }
        Instant now = clock.instant();
        Set<Long> candidates = journal.sealedBy(now.minus(config.grace()).toEpochMilli());
        for (JournalRef open : openSaves.get()) candidates.remove(open.segment());   // read before the pins are asked
        Set<Long> gone = candidates.isEmpty() ? Set.of() : rows.sweep(journal.node(), candidates, now.minus(config.keepMaxAge())).await();
        for (long n : gone) {
            if (!unlinked(n, () -> journal.swept(n))) unlinking.add(n);
        }
        LOG.log(System.Logger.Level.DEBUG, "swept journal segments {0} of node {1}", gone, journal.node());
        return gone;
    }

    /** Runs segment {@code n}'s unlink: false when it failed (a WARNING; the segment's bytes still count, the next pass tries again). */
    private boolean unlinked(long n, Unlink unlink) {
        try {
            unlink.run();
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "the file of swept journal segment " + n + " of node " + journal.node()
                    + " could not be unlinked; the next sweep tries again", e);
            return false;
        }
    }

    private interface Unlink {
        void run() throws IOException;
    }

    /** A pass begins, unless closed: counted until {@link #leave}. */
    private boolean enter() {
        lock.lock();
        try {
            if (closed) return false;
            running++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void leave() {
        lock.lock();
        try {
            running--;
            idle.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Stops the schedule (a pass due is not run) and waits for a pass in flight; a second close finds nothing to do. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            if (next != null) next.cancel();
            while (running > 0) idle.awaitUninterruptibly();
        } finally {
            lock.unlock();
        }
    }
}
