package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.core.admission.Admission;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static dev.rail8.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Runs} on a fake clock: what a stop interrupts, what it leaves alone, and what the guarded steps see. */
@Timeout(30)
class RunsTest {

    static final Runs.Cancelled ALICE = new Runs.Cancelled("alice", Optional.of("wrong order"));

    final FakeRuntime rt = FakeRuntime.create();
    final Runs runs = new Runs(rt.clock(), rt.timer(), Duration.ofSeconds(30));
    final Envelope e = rt.ingress("orders", Headers.empty(), Buffer.buffer("<a/>"));

    static StepConfig step(int index, String name) {
        return new StepConfig(index, "probe", name, false, Map.of());
    }

    /** {@code stages} under the head of a run with {@code limit}, each guarded as step 1, 2, … */
    Pipeline pipeline(Duration limit, Stage... stages) {
        List<Stage> all = new java.util.ArrayList<>();
        all.add(runs.head(limit));
        for (int i = 0; i < stages.length; i++) all.add(Runs.guard(stages[i], step(i + 1, "s" + (i + 1))));
        return Pipeline.of(all);
    }

    @Test
    void aStopInTheLastStepKeepsItsResultAndLeavesNoInterruptBehind() {
        Envelope out = pipeline(Duration.ofMinutes(1), Stage.of(x -> {
            assertTrue(runs.stop(x.id(), ALICE), "its own run, running");
            assertTrue(Thread.currentThread().isInterrupted(), "the stop interrupts the run's thread while it is inside");
            return x.withHeader("done", "yes");
        })).apply(e);
        assertEquals("yes", out.headers().get("done"), "a step that returned keeps its result");
        assertFalse(Thread.currentThread().isInterrupted(), "the run's own interrupt is cleared before its thread leaves");
        assertFalse(runs.stop(e.id(), ALICE), "nothing runs any more");
    }

    @Test
    void aStopRefusesTheNextStepWithItsWhy() {
        AtomicInteger second = new AtomicInteger();
        RunStoppedException stopped = assertThrows(RunStoppedException.class, () -> pipeline(Duration.ofMinutes(1),
                Stage.of(x -> { runs.stop(x.id(), ALICE); Thread.interrupted(); return x; }),
                Stage.of(x -> { second.incrementAndGet(); return x; })).apply(e));
        assertSame(ALICE, stopped.why());
        assertEquals("cancelled by alice: wrong order", stopped.getMessage());
        assertEquals(0, second.get(), "the next step does not start");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void aTimeLimitInterruptsAWaitingStepWhoseFailureCarriesIt() throws Exception {
        Promise<Void> never = Promise.promise();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        CompletableFuture<Boolean> interruptedAfter = new CompletableFuture<>();
        Thread run = Thread.ofVirtual().start(() -> {
            try {
                pipeline(Duration.ofSeconds(5), Stage.of(x -> { never.future().await(); return x; })).apply(e);
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
            interruptedAfter.complete(Thread.currentThread().isInterrupted());
        });
        until(() -> run.getState() == Thread.State.WAITING, () -> "the step waits: " + run.getState());
        assertFalse(thrown.isDone(), "nothing stops it before its time");
        rt.clock().advance(Duration.ofSeconds(5));

        RunStoppedException stopped = assertInstanceOf(RunStoppedException.class, thrown.get(10, SECONDS));
        assertEquals(new Runs.TimeLimit(Duration.ofSeconds(5)), stopped.why());
        assertEquals("its time limit of PT5S passed", stopped.getMessage());
        assertInstanceOf(InterruptedException.class, stopped.getCause(), "what the step was doing when it stopped");
        assertFalse(interruptedAfter.get(10, SECONDS), "the thread leaves with its interrupt flag clear");
        assertEquals(0, rt.timer().pending(), "no timer of the run is left");
    }

    /**
     * A run its time limit stopped returns only once its open send settled, through nothing of its own; an interrupt from another
     * (a delivery's stop deadline) ends the wait, and the thread leaves with that interrupt kept.
     */
    @Test
    void aStoppedRunWaitsForItsSendUntilAnotherInterruptsIt() throws Exception {
        Promise<Envelope> send = Promise.promise();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        CompletableFuture<Boolean> interruptedAfter = new CompletableFuture<>();
        Thread run = Thread.ofVirtual().start(() -> {
            try {
                pipeline(Duration.ofSeconds(5), Stage.of(x -> {
                    Runs.current().watch(send.future());
                    return send.future().await();
                })).apply(e);
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
            interruptedAfter.complete(Thread.interrupted());
        });
        until(() -> run.getState() == Thread.State.WAITING, () -> "the step waits for its send: " + run.getState());
        rt.clock().advance(Duration.ofSeconds(5));
        until(() -> Arrays.stream(run.getStackTrace()).anyMatch(f -> f.getMethodName().equals("awaitSends")) && run.getState() == Thread.State.WAITING,
                () -> "the stopped run waits for its send: " + Arrays.toString(run.getStackTrace()));
        assertFalse(thrown.isDone(), "the send is still out");

        run.interrupt();
        RunStoppedException stopped = assertInstanceOf(RunStoppedException.class, thrown.get(10, SECONDS));
        assertEquals(new Runs.TimeLimit(Duration.ofSeconds(5)), stopped.why());
        assertTrue(interruptedAfter.get(10, SECONDS), "the other's interrupt is kept");
        assertFalse(send.future().isComplete(), "the send is left as it is");
        assertFalse(runs.stop(e.id(), ALICE), "the run is no longer registered");
    }

    /**
     * A sync delivery's stop deadline ends a stopped run's wait for its send even when its interrupt lands while the stop's own
     * is still pending on the run's thread: the one flag then holds both, and the run clears it as its own. The delivery answers
     * its cancel; the send is left as it is.
     */
    @Test
    void aDeliverysStopDeadlineEndsAStoppedRunsWaitThoughItsInterruptMergedWithTheStops() throws Exception {
        Promise<Envelope> send = Promise.promise();
        Object gate = new Object();
        Delivery delivery = Delivery.sync(new Admission(8, 1 << 20), pipeline(Duration.ofSeconds(5), Stage.of(x -> {
            Runs.current().watch(send.future());
            synchronized (gate) {   // no interrupt wakes a thread blocked here: both land on its flag before it awaits the send
                return send.future().await();
            }
        })), rt.timer());
        CompletableFuture<Ack> answer = new CompletableFuture<>();
        synchronized (gate) {
            Thread caller = Thread.ofVirtual().start(() -> delivery.apply(e).onComplete(answer::complete, answer::completeExceptionally));
            until(() -> caller.getState() == Thread.State.BLOCKED, () -> "the step waits for the gate: " + caller.getState());
            rt.clock().advance(Duration.ofSeconds(5));   // the time limit: the stop's interrupt, still pending
            delivery.stop(Duration.ZERO);                // the deadline at once: its interrupt on the same flag
        }

        assertEquals(new Ack.Nack("cancelled: flow orders stopping after PT0S", true), answer.get(10, SECONDS), "the delivery's cancel");
        assertFalse(send.future().isComplete(), "the send is left as it is");
        assertFalse(runs.stop(e.id(), ALICE), "the run is no longer registered");
    }

    /**
     * A run still going its patience after its time limit — its step does not answer the interrupt — is logged once as a WARNING
     * naming the message, the step and the thread. Nothing ends it: the step that returns at last keeps its result.
     */
    @Test
    void aRunStillGoingItsPatienceAfterItsTimeLimitIsLoggedOnce() throws Exception {
        FlowWorkerTest.Logs logs = FlowWorkerTest.Logs.attach();
        try {
            Object gate = new Object();
            CompletableFuture<Envelope> out = new CompletableFuture<>();
            synchronized (gate) {
                Thread run = Thread.ofVirtual().name("run-of-a-stuck-step").start(() -> {
                    try {
                        out.complete(pipeline(Duration.ofSeconds(5), Stage.of(x -> {
                            synchronized (gate) {   // no interrupt wakes a thread blocked here: a step that does not answer it
                                return x.withHeader("done", "yes");
                            }
                        })).apply(e));
                    } catch (Throwable t) {
                        out.completeExceptionally(t);
                    }
                });
                until(() -> run.getState() == Thread.State.BLOCKED, () -> "the step is stuck: " + run.getState());
                rt.clock().advance(Duration.ofSeconds(5));
                rt.clock().advance(Duration.ofSeconds(30).minusMillis(1));
                assertEquals(List.of(), logs.at(Level.WARNING, e.id()), "not before its patience passed");
                rt.clock().advance(Duration.ofMillis(1));
                List<String> warned = logs.at(Level.WARNING, e.id());
                assertEquals(1, warned.size(), logs.all().toString());
                assertTrue(warned.getFirst().contains("still runs PT30S after its time limit of PT5S on thread run-of-a-stuck-step, last in step 's1'"),
                        warned.getFirst());
                rt.clock().advance(Duration.ofSeconds(30));
                assertEquals(1, logs.at(Level.WARNING, e.id()).size(), "logged once");
            }
            assertEquals("yes", out.get(10, SECONDS).headers().get("done"), "the step that returns at last keeps its result");
        } finally {
            logs.detach();
        }
    }

    @Test
    void aRunStoppedFromItsStartRunsNoStep() {
        AtomicInteger ran = new AtomicInteger();
        RunStoppedException stopped = assertThrows(RunStoppedException.class,
                () -> Runs.stoppedFromStart(() -> ALICE, () -> pipeline(Duration.ofMinutes(1), Stage.of(x -> { ran.incrementAndGet(); return x; })).apply(e)));
        assertSame(ALICE, stopped.why());
        assertNull(stopped.getCause());
        assertEquals(0, ran.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void aFailureBeforeAnyStopPassesUnchangedAndNoRunIsBoundOutside() {
        IllegalStateException failure = new IllegalStateException("broken");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> pipeline(Duration.ofMinutes(1), Stage.of(x -> { throw failure; })).apply(e)));
        assertEquals(Optional.empty(), Runs.why());
        Envelope out = Runs.guard(Stage.of(x -> x), step(1, "s1")).apply(e, x -> x);
        assertSame(e, out, "without a run the guard is the stage");
    }
}
