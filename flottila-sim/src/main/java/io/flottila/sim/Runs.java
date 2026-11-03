package io.flottila.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * The message threads of a run and where each stands (D-101): a run is RUNNING from its unit's start until it is HELD by the
 * partner, PARKED on a Retry backoff, or ended — its end seen by a join on a helper virtual thread, never by polling thread
 * state. A START run is registered by the driver with its thread before it starts; a worker run is expected by message id when
 * the queue hands its item out (a claim on the driver, a fast path on the finishing thread) and binds its thread at its first
 * simulation call, the timeline read. {@link #quiesce()} waits until no run is RUNNING, and throws when a run's end could not
 * be recorded (the driver's SIM ERROR) instead of waiting on it.
 */
final class Runs {

    enum State { RUNNING, HELD, PARKED }

    /**
     * One run of one message: a send's thread, or a worker's run of an item. A worker run waits at its first call until the
     * thread that handed its item out is past {@code FlowWorker}'s start of it — the driver back from its poll, a finishing run
     * ended — so the run's end is always heard on the run's own thread, never on the one that started it.
     */
    static final class Run {
        final String id;
        final String worker;   // null: a send's run
        final Thread starter;  // the thread the item was handed out on; null for a send's run
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        Thread thread;         // guarded by the lock; null until a worker run binds
        State state = State.RUNNING;   // guarded by the lock

        Run(String id, String worker, Thread starter) {
            this.id = id;
            this.worker = worker;
            this.starter = starter;
        }
    }

    /** A partner call held until its tick: the driver's RELEASE completes {@code released}. */
    record Held(Run run, long releaseTick, CompletableFuture<Void> released) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<String, Run> active = new LinkedHashMap<>();   // guarded by lock: by message id
    private final Map<Thread, Run> byThread = new HashMap<>();       // guarded by lock
    private final List<Held> held = new ArrayList<>();               // guarded by lock
    private IllegalStateException endFailure;                        // guarded by lock: the first run whose end was not recorded
    private volatile Thread driver;

    /** The calling thread drives the run. */
    void drive() {
        driver = Thread.currentThread();
    }

    boolean onDriver() {
        return Thread.currentThread() == driver;
    }

    /** A send's run on {@code thread}, not started yet: registered first, so its first call finds it. */
    Run start(String id, Thread thread) {
        Run run = new Run(id, null, null);
        lock.lock();
        try {
            run.thread = thread;
            active.put(id, run);
            byThread.put(thread, run);
        } finally {
            lock.unlock();
        }
        return run;
    }

    /**
     * A worker run of {@code id} is about to start on a thread the worker makes, its item handed out on the calling thread; it
     * binds at {@link #bind} and goes on once {@link #open} is called for the calling thread or that thread ends.
     */
    void expect(String id, String worker) {
        lock.lock();
        try {
            active.put(id, new Run(id, worker, Thread.currentThread()));
        } finally {
            lock.unlock();
        }
    }

    /** The runs whose items were handed out on {@code starter} may go on: it is past their start. */
    void open(Thread starter) {
        lock.lock();
        try {
            for (Run r : active.values()) if (r.starter == starter) r.gate.complete(null);
        } finally {
            lock.unlock();
        }
    }

    /** The calling thread is the expected worker run of {@code id}: bound, its end joined; it waits at the run's gate. */
    void bind(String id) {
        Run run;
        lock.lock();
        try {
            run = active.get(id);
            run.thread = Thread.currentThread();
            byThread.put(run.thread, run);
        } finally {
            lock.unlock();
        }
        joinEnd(run);
        run.gate.join();   // never fails, never interrupted
    }

    /** Sees the run's thread end on a helper virtual thread and drops the run then. Its thread must be started. */
    void joinEnd(Run run) {
        Thread.ofVirtual().name("sim-join-" + run.id).start(new FutureTask<Void>(() -> {
            run.thread.join();
            ended(run);
            return null;
        }) {
            @Override protected void done() {   // a failed join or drop would leave the run RUNNING: quiesce throws it instead
                if (state() == java.util.concurrent.Future.State.FAILED) endFailed(run, exceptionNow());
            }
        });
    }

    /** The run on the calling thread. */
    Run current() {
        lock.lock();
        try {
            return byThread.get(Thread.currentThread());
        } finally {
            lock.unlock();
        }
    }

    /** The calling run is held by the partner until {@code releaseTick}; the driver may go on. */
    void hold(long releaseTick, CompletableFuture<Void> released) {
        lock.lock();
        try {
            Run run = byThread.get(Thread.currentThread());
            held.add(new Held(run, releaseTick, released));
            set(run, State.HELD);
        } finally {
            lock.unlock();
        }
    }

    /** {@code run} parks on a Retry backoff the timer defers as a WAKE; the driver may go on. */
    void park(Run run) {
        lock.lock();
        try {
            set(run, State.PARKED);
        } finally {
            lock.unlock();
        }
    }

    /** The held calls due by {@code tick}, taken out, by release tick then message id. */
    List<Held> due(long tick) {
        lock.lock();
        try {
            List<Held> due = held.stream().filter(h -> h.releaseTick() <= tick)
                    .sorted(Comparator.comparingLong(Held::releaseTick).thenComparing(h -> h.run().id)).toList();
            held.removeAll(due);
            return due;
        } finally {
            lock.unlock();
        }
    }

    /** The driver lets {@code run} go on: RUNNING until it is held, parked or ended. */
    void resume(Run run) {
        lock.lock();
        try {
            run.state = State.RUNNING;
        } finally {
            lock.unlock();
        }
    }

    /** Waits until no run is RUNNING: every one is held, parked or ended; throws once a run's end could not be recorded. */
    void quiesce() throws InterruptedException {
        lock.lock();
        try {
            while (endFailure == null && active.values().stream().anyMatch(r -> r.state == State.RUNNING)) changed.await();
            if (endFailure != null) throw new IllegalStateException(endFailure.getMessage(), endFailure.getCause());
        } finally {
            lock.unlock();
        }
    }

    /** Runs not ended. */
    int active() {
        lock.lock();
        try {
            return active.size();
        } finally {
            lock.unlock();
        }
    }

    /** Calls held by the partner. */
    int held() {
        lock.lock();
        try {
            return held.size();
        } finally {
            lock.unlock();
        }
    }

    /** Runs of {@code worker} not ended. */
    long ofWorker(String worker) {
        lock.lock();
        try {
            return active.values().stream().filter(r -> worker.equals(r.worker)).count();
        } finally {
            lock.unlock();
        }
    }

    /** Every RUNNING run with its stack, or that its thread never bound: what a hang waits on. */
    String unclassified() {
        lock.lock();
        try {
            return active.values().stream().filter(r -> r.state == State.RUNNING)
                    .map(r -> "run " + r.id + (r.thread == null ? ": expected, its thread never made a simulation call"
                            : " on " + r.thread + ":\n\tat " + Arrays.stream(r.thread.getStackTrace()).map(String::valueOf)
                            .collect(Collectors.joining("\n\tat "))))
                    .collect(Collectors.joining("\n"));
        } finally {
            lock.unlock();
        }
    }

    private void endFailed(Run run, Throwable cause) {
        lock.lock();
        try {
            if (endFailure == null) endFailure = new IllegalStateException("the end of run " + run.id + " was not recorded", cause);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void ended(Run run) {
        lock.lock();
        try {
            active.remove(run.id, run);
            byThread.remove(run.thread, run);
            open(run.thread);   // reentrant: the runs it handed items to may go on
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void set(Run run, State state) {
        run.state = state;
        changed.signalAll();
    }
}
