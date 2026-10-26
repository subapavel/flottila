package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A pipeline — or a step, {@link #step(String)} — that holds each message's thread until the test answers that message,
 * the way a step parks in Vert.x {@code Future.await()} on a partner (D-056 stop deadline tests). {@link Run#reply} and
 * {@link Run#fail} complete the future the run awaits; a failure is thrown as it is, a checked one sneakily. How a run meets
 * an interrupt of its thread depends on the factory:
 * <ul>
 * <li>{@link #awaiting()}: as Vert.x does — {@code await} throws {@code InterruptedException} sneakily (the flag cleared);</li>
 * <li>{@link #awaitingThenThrowing}: catches that and throws the given failure instead (a step turning the wake-up into
 * its own fault);</li>
 * <li>{@link #stubborn}: parks until its thread is interrupted, without clearing the flag, then replies — or throws — with
 * the flag still set (a pipeline that ignores the cancel).</li>
 * </ul>
 * Each run records its thread and whether it met an interrupt ({@code await} threw it, or the flag was set when
 * {@code await} returned).
 */
final class HeldPipeline implements Pipeline {

    /** The header a {@link Run#reply} carries its marker in. */
    static final String H_ANSWER = "x-answer";
    /** The marker a {@link #stubborn} run replies with. */
    static final String DESPITE_INTERRUPT = "despite-interrupt";
    /** How long anything here waits before it gives up (a leak bound, never a timing). */
    static final long BOUND_SECONDS = 10;

    /** One run of one message. */
    static final class Run {
        final Envelope in;
        final Thread thread;
        private final Promise<Envelope> answer = Promise.promise();
        volatile boolean interrupted;

        Run(Envelope in, Thread thread) {
            this.in = in;
            this.thread = thread;
        }

        /** Answers the run with its envelope carrying {@code marker} in {@link #H_ANSWER}; a no-op once answered. */
        void reply(String marker) { answer.tryComplete(in.withHeader(H_ANSWER, marker)); }

        /** Answers the run with {@code failure}, thrown from its {@code await}; a no-op once answered. */
        void fail(Throwable failure) { answer.tryFail(failure); }

        /** Waits for the run's thread to end (an at-most-once run: its bookkeeping after the pipeline is done). */
        void joined() {
            try {
                if (!thread.join(Duration.ofSeconds(BOUND_SECONDS))) {
                    org.junit.jupiter.api.Assertions.fail("run of " + in.id() + " on " + thread + " did not end within " + BOUND_SECONDS + " s; it sits at:\n  "
                            + Arrays.stream(thread.getStackTrace()).map(String::valueOf).collect(Collectors.joining("\n  ")));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                org.junit.jupiter.api.Assertions.fail("interrupted joining the run of " + in.id());
            }
        }
    }

    private enum Kind { AWAIT, AWAIT_THEN_THROW, STUBBORN_REPLY, STUBBORN_THROW }

    private final Kind kind;
    private final Supplier<? extends Throwable> onInterrupt;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> reached = new ConcurrentHashMap<>();
    private final AtomicInteger entered = new AtomicInteger();

    private HeldPipeline(Kind kind, Supplier<? extends Throwable> onInterrupt) {
        this.kind = kind;
        this.onInterrupt = onInterrupt;
    }

    /** Awaits the answer; an interrupt surfaces as the sneaky {@code InterruptedException} Vert.x throws. */
    static HeldPipeline awaiting() { return new HeldPipeline(Kind.AWAIT, null); }

    /** Awaits the answer; an interrupt is met by throwing {@code failure.get()} instead. */
    static HeldPipeline awaitingThenThrowing(Supplier<? extends Throwable> failure) { return new HeldPipeline(Kind.AWAIT_THEN_THROW, failure); }

    /** Waits for an interrupt, keeps the flag, then replies with {@link #DESPITE_INTERRUPT} ({@code replies}) or throws. */
    static HeldPipeline stubborn(boolean replies) { return new HeldPipeline(replies ? Kind.STUBBORN_REPLY : Kind.STUBBORN_THROW, null); }

    /** Throws {@code failure} from code that declares none, as Vert.x {@code Future.await()} does. */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    /** This fixture as a step of type {@code type}: it parks, then hands the answer on. */
    StepFactory step(String type) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources resources) { return (e, rest) -> rest.apply(HeldPipeline.this.apply(e)); }
        };
    }

    /** Messages that entered so far. */
    int entered() { return entered.get(); }

    /** The run of {@code messageId}, once it is held; fails the test when it does not arrive within the bound. */
    Run run(String messageId) {
        try {
            if (!latch(messageId).await(BOUND_SECONDS, TimeUnit.SECONDS)) fail("message " + messageId + " never reached the held step");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for " + messageId);
        }
        return runs.get(messageId);
    }

    /** The run of {@code messageId} if it is held, else null; never waits. */
    Run runIfHeld(String messageId) { return runs.get(messageId); }

    private CountDownLatch latch(String messageId) {
        return reached.computeIfAbsent(messageId, id -> new CountDownLatch(1));
    }

    @Override
    public Envelope apply(Envelope e) {
        Run run = new Run(e, Thread.currentThread());
        runs.put(e.id(), run);
        entered.incrementAndGet();
        latch(e.id()).countDown();   // the run is registered before it is signalled
        return switch (kind) {
            case AWAIT, AWAIT_THEN_THROW -> await(run);
            case STUBBORN_REPLY, STUBBORN_THROW -> stubborn(run);
        };
    }

    private Envelope await(Run run) {
        try {
            Envelope out = run.answer.future().await();
            if (Thread.currentThread().isInterrupted()) run.interrupted = true;
            return out;
        } catch (Exception x) {   // a sneaky InterruptedException from await, or the failure the test answered with
            if (x instanceof InterruptedException) {
                run.interrupted = true;
                if (kind == Kind.AWAIT_THEN_THROW) throw sneaky(onInterrupt.get());
            }
            throw x;
        }
    }

    private Envelope stubborn(Run run) {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        while (!Thread.currentThread().isInterrupted()) {   // isInterrupted, not interrupted(): the flag stays set
            if (System.nanoTime() > giveUp) throw new IllegalStateException("stubborn run of " + run.in.id() + " was never interrupted");
            LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
        }
        run.interrupted = true;
        if (kind == Kind.STUBBORN_THROW) throw new IllegalStateException("gave up after the interrupt, flag still set");
        return run.in.withHeader(H_ANSWER, DESPITE_INTERRUPT);
    }
}
