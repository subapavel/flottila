package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A step ({@code type: gate}) that holds each run of a message at its entry until the test releases that run, and wraps the
 * rest of the pipeline, so a run counts as active from its entry until everything after the gate returned. It records which
 * thread each run is on (a test joins it to know the worker's bookkeeping after the run is done), how many runs are active
 * at once overall ({@link #peakActive()}) and every entry of a message that already had an active run ({@link #overlaps()}):
 * two holders of one item at once. {@link #open()} lets every run through, held ones included.
 */
final class RunGate implements StepFactory {

    static final Map<String, Object> STEP = Map.of("type", "gate", "name", "gate");

    /** How long a held run waits for its release before it gives up (a leak bound, never a timing). */
    private static final long LEAK_BOUND_SECONDS = 60;

    /** One run of one message through the gate. */
    static final class Run {
        final String messageId;
        final Thread thread;
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean exited;

        Run(String messageId, Thread thread) {
            this.messageId = messageId;
            this.thread = thread;
        }

        void release() { release.countDown(); }

        boolean released() { return release.getCount() == 0; }

        /** Waits for the run's thread to end: the worker's bookkeeping for the item on that thread is done. */
        void joined() {
            try {
                if (!thread.join(Duration.ofSeconds(5))) {
                    fail("run of " + messageId + " on " + thread + " did not end within 5 s; it sits at:\n  "
                            + Arrays.stream(thread.getStackTrace()).map(String::valueOf).collect(Collectors.joining("\n  ")));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted joining the run of " + messageId);
            }
        }

        @Override public String toString() { return "run of " + messageId + (exited ? " (exited)" : released() ? " (released)" : " (held)"); }
    }

    private final List<Run> runs = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> activeById = new HashMap<>();   // guarded by this
    private final List<String> overlaps = new CopyOnWriteArrayList<>();
    private final AtomicInteger exited = new AtomicInteger();
    private int active;                                                 // guarded by this
    private int peak;                                                   // guarded by this
    private volatile boolean open;

    @Override public String type() { return "gate"; }

    @Override public String configSchema() { return "{}"; }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        return this::pass;
    }

    private Envelope pass(Envelope e, Pipeline rest) {
        Run run = new Run(e.id(), Thread.currentThread());
        synchronized (this) {
            int mine = activeById.merge(e.id(), 1, Integer::sum);
            if (mine > 1) overlaps.add(e.id() + " entered while " + (mine - 1) + " other run(s) of it were active");
            active++;
            peak = Math.max(peak, active);
            runs.add(run);   // after the counters: whoever sees the run sees its counts
        }
        try {
            if (!open && !run.release.await(LEAK_BOUND_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate: the run of " + e.id() + " was never released");
            }
            return rest.apply(e);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gate: interrupted holding " + e.id(), interrupted);
        } finally {
            synchronized (this) {
                activeById.merge(e.id(), -1, Integer::sum);
                active--;
            }
            run.exited = true;
            exited.incrementAndGet();
        }
    }

    /** Every run from now on passes at once, and every held run is released. */
    void open() {
        open = true;
        runs.forEach(Run::release);
    }

    List<Run> runs() { return List.copyOf(runs); }

    List<Run> runsOf(String messageId) { return runs.stream().filter(r -> r.messageId.equals(messageId)).toList(); }

    /** Runs waiting at the gate for their release. */
    List<Run> held() { return open ? List.of() : runs.stream().filter(r -> !r.released()).toList(); }

    int entered() { return runs.size(); }

    int exited() { return exited.get(); }

    synchronized int peakActive() { return peak; }

    List<String> overlaps() { return List.copyOf(overlaps); }

    /** Joins every run that was let through (released, or passed an open gate). */
    void joinPassed() {
        for (Run r : runs) if (open || r.released()) r.joined();
    }
}
