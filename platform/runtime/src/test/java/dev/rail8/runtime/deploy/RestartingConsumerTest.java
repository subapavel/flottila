package dev.rail8.runtime.deploy;

import dev.rail8.api.Consumer;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static dev.rail8.runtime.deploy.LeaseLog.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's restart of a consumer that does not start or breaks: one attempt on {@code start()}, every later one after a delay
 * of 1, 2, 4, 8, 16, 30, 30 … seconds on the injected timer, a fresh consumer each time, never two at once, nothing after the
 * stop. Time is the fake clock's: the timer fires on the test thread, each attempt runs on a virtual thread of its own, and a
 * case waits for what that thread does by spinning on a condition, never by sleeping.
 */
@Timeout(60)
class RestartingConsumerTest {

    static final Instant T0 = FakeRuntime.T0;
    static final Duration BOUND = Duration.ofSeconds(10);
    static final String FLOW = "shop/in";

    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    /** Every inner consumer built, in order: {@code c1} is the one given first. */
    final List<Inner> built = new CopyOnWriteArrayList<>();
    final List<String> log = new CopyOnWriteArrayList<>();
    final AtomicInteger refusals = new AtomicInteger();
    /** What the next builds do instead of building an inner consumer, in order: throw, or answer null. */
    final java.util.concurrent.ConcurrentLinkedQueue<Supplier<Consumer>> builds = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger rail8 = Logger.getLogger("dev.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    final Supplier<Consumer> fresh = () -> {
        Supplier<Consumer> instead = builds.poll();
        if (instead != null) return instead.get();
        if (refusals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalArgumentException("the component refused the consumer");
        return inner();
    };
    RestartingConsumer restarting;
    Level before;

    @BeforeEach
    void build() {
        before = rail8.getLevel();
        rail8.setLevel(Level.ALL);   // the DEBUG lines too
        capture.setLevel(Level.ALL);
        rail8.addHandler(capture);
    }

    @AfterEach
    void unhook() {
        built.forEach(Inner::release);
        if (restarting != null) restarting.stop();
        rail8.removeHandler(capture);
        rail8.setLevel(before);
    }

    // --- fixture

    /**
     * An inner consumer, {@code c<n>}: its start succeeds unless {@link #failStart} or {@link #holdStart} was set, its stop
     * completes unless {@link #holdStop} was set, and {@link #breakNow} fails its {@code broken()}. Its start, failed start,
     * stop and settled stop go to the case's log; {@link #onStop} runs at the top of its stop.
     */
    final class Inner implements Consumer {
        final int n;
        final Promise<Void> broken = Promise.promise();
        volatile String startFailure;
        volatile Promise<Void> heldStart;
        volatile Promise<Void> heldStop;
        volatile boolean breakInsideStart;
        volatile Runnable onStop;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        /** The thread its start was called on. */
        volatile Thread startedOn;

        Inner(int n) { this.n = n; }

        Inner failStart(String why) { startFailure = why; return this; }

        Inner holdStart() { heldStart = Promise.promise(); return this; }

        Inner holdStop() { heldStop = Promise.promise(); return this; }

        void breakNow(String why) { broken.tryFail(new IllegalStateException(why)); }

        void release() {
            Promise<Void> s = heldStart;
            if (s != null) s.tryComplete();
            Promise<Void> t = heldStop;
            if (t != null) t.tryComplete();
        }

        @Override public Future<Void> start() {
            starts.incrementAndGet();
            startedOn = Thread.currentThread();
            log.add("start c" + n);
            if (breakInsideStart) breakNow("broke before its start completed");
            if (startFailure != null) {
                log.add("start failed c" + n);
                return Future.failedFuture(new IllegalStateException(startFailure));
            }
            Promise<Void> held = heldStart;
            return held == null ? Future.succeededFuture() : held.future();
        }

        @Override public Future<Void> stop() {
            stops.incrementAndGet();
            log.add("stop c" + n);
            Runnable hook = onStop;
            if (hook != null) hook.run();
            Promise<Void> held = heldStop;
            return (held == null ? Future.<Void>succeededFuture() : held.future()).andThen(done -> log.add("stopped c" + n));
        }

        @Override public Future<Void> broken() { return broken.future(); }
    }

    /** The next inner consumer: {@code c<n>}, set up by {@link #next} when the case armed it. */
    Inner inner() {
        Inner c = new Inner(built.size() + 1);
        Arm arm = armed.poll();
        if (arm != null) arm.apply(c);
        built.add(c);
        log.add("build c" + c.n);
        return c;
    }

    interface Arm { void apply(Inner c); }

    final java.util.concurrent.ConcurrentLinkedQueue<Arm> armed = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Sets up the next inner consumers built, in order. */
    void next(Arm... arms) { armed.addAll(List.of(arms)); }

    RestartingConsumer restarting(Consumer first, boolean polls) {
        restarting = new RestartingConsumer(FLOW, first, fresh, polls, BOUND, timer, clock);
        return restarting;
    }

    Inner c(int n) { return built.get(n - 1); }

    /** Waits until the consumer waits for its next attempt, the one task the timer holds, due {@code delay} from now. */
    void waitsFor(Duration delay, int failures) {
        until(() -> restarting.waiting().isPresent() && restarting.waiting().get().failures() == failures && timer.pending() == 1,
                "the consumer waits for attempt " + (failures + 1) + "; log " + log);
        assertEquals(delay, timer.nextDelay(), "the delay after failure " + failures + "; log " + log);
        assertEquals(clock.instant().plus(delay), restarting.waiting().get().next(), "the next attempt is due then");
    }

    /** Waits until {@code c<n>} was built and its start called. */
    void startedAttempt(int n) {
        until(() -> built.size() >= n && c(n).starts.get() == 1, "c" + n + " was built and started; log " + log);
    }

    /** Waits until the attempt with {@code c<n>} succeeded: the consumer runs, nothing is due. */
    void runs(int n) {
        startedAttempt(n);
        until(() -> restarting.waiting().isEmpty() && timer.pending() == 0, "c" + n + " runs; log " + log);
    }

    List<LogRecord> logged(Level level) {
        return logged.stream().filter(r -> r.getLevel() == level && r.getMessage().contains(FLOW)).toList();
    }

    // --- one attempt on start()

    @Test
    void aStartThatSucceedsRunsTheConsumerAndSchedulesNothing() {
        Inner c1 = inner();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the start completes");
        assertTrue(start.succeeded(), "as the inner start did: " + start);
        assertEquals(0, timer.pending(), "nothing due: its bound was cancelled");
        assertTrue(restarting.waiting().isEmpty());
        assertEquals(List.of("build c1", "start c1"), log);
    }

    @Test
    void aFailedStartFailsWithItsCauseStopsThatConsumerAndSchedulesNothing() {
        Inner c1 = inner().failStart("address in use");
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the start completes");
        assertEquals("address in use", start.cause().getMessage());
        until(() -> c1.stops.get() == 1, "the consumer whose start failed is stopped: it may hold a session half open");
        assertEquals(0, timer.pending(), "nothing tried again: the deploy refuses it");
        clock.advance(Duration.ofHours(1));
        assertEquals(1, built.size(), "no fresh consumer: " + log);
        assertTrue(restarting.waiting().isEmpty(), "not waiting for an attempt");
    }

    @Test
    void theStartCallsTheInnerStartOnTheCallersThreadAndDoesNotWaitForIt() {
        Inner c1 = inner().holdStart();
        Future<Void> start = restarting(c1, false).start();
        assertSame(Thread.currentThread(), c1.startedOn, "the inner start was called before start() returned, on its caller's thread");
        assertFalse(start.isComplete(), "the inner start is held");
        c1.heldStart.complete();
        until(start::isComplete, "completes once the inner start did");
        assertTrue(start.succeeded());
    }

    /** An attempt after the first builds its consumer and calls its start on a virtual thread of its own, never the timer's. */
    @Test
    void aLaterAttemptStartsItsConsumerOnAVirtualThreadOfItsOwn() {
        Future<Void> start = restarting(inner().failStart("address in use"), false).start();
        until(start::isComplete, "c1's start failed");
        restarting.retry(start.cause());
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        runs(2);
        Thread on = built.get(1).startedOn;
        assertTrue(on.isVirtual() && on != Thread.currentThread(), "on a virtual thread of its own: " + on);
    }

    @Test
    void aStartThatOutlastsTheBoundFailsAndItsConsumerStopsOnceItsStartCompleted() {
        Inner c1 = inner().holdStart();
        Future<Void> start = restarting(c1, false).start();
        until(() -> c1.starts.get() == 1 && timer.pending() == 1, "the start is called and bounded on the timer");
        assertEquals(BOUND, timer.nextDelay());
        clock.advance(BOUND);
        until(start::isComplete, "the bound fails the attempt");
        assertInstanceOf(TimeoutException.class, start.cause(), "" + start.cause());
        assertEquals(0, c1.stops.get(), "no stop meets a consumer still starting");
        c1.heldStart.complete();
        until(() -> c1.stops.get() == 1, "a late success is followed by a stop");
        Future<Void> stop = restarting.stop();
        until(stop::isComplete, "the stop does not wait for anything more");
        assertTrue(stop.succeeded());
    }

    // --- retry after a failed start

    @Test
    void retryTriesAgainWithADelayThatDoublesUpToThirtySeconds() {
        Inner c1 = inner().failStart("partner down");
        next(c -> c.failStart("partner down"), c -> c.failStart("partner down"), c -> c.failStart("partner down"),
                c -> c.failStart("partner down"), c -> c.failStart("partner down"), c -> c.failStart("partner down"));
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the first attempt failed");
        restarting.retry(start.cause());
        long[] seconds = {1, 2, 4, 8, 16, 30, 30};
        for (int i = 0; i < seconds.length; i++) {
            waitsFor(Duration.ofSeconds(seconds[i]), i + 1);
            clock.advance(Duration.ofSeconds(seconds[i]).minusMillis(1));
            assertEquals(i + 1, built.size(), "nothing built before the delay ran out: " + log);
            clock.advance(Duration.ofMillis(1));
            startedAttempt(i + 2);
        }
        runs(8);
        until(() -> !logged(Level.INFO).isEmpty(), "the INFO, written once it runs");
        assertEquals(1, logged(Level.INFO).size(), "one INFO when it runs again: " + logged);
        assertTrue(logged(Level.INFO).getFirst().getMessage().contains("after 7 failures in a row"), logged(Level.INFO).getFirst().getMessage());
        for (int n = 1; n < 8; n++) assertEquals(1, c(n).stops.get(), "c" + n + ", whose start failed, was stopped once");
        assertEquals(0, c(8).stops.get(), "the one that runs is not stopped");
    }

    @Test
    void theFirstFailureIsAWarningWithItsStackTraceTheOthersAreDebug() {
        Inner c1 = inner().failStart("partner down");
        next(c -> c.failStart("partner down"));
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the first attempt failed");
        restarting.retry(start.cause());
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        waitsFor(Duration.ofSeconds(2), 2);
        assertEquals(1, logged(Level.WARNING).size(), "one WARNING: " + logged);
        LogRecord warning = logged(Level.WARNING).getFirst();
        assertTrue(warning.getMessage().contains("did not start") && warning.getMessage().contains("partner down")
                && warning.getMessage().contains("PT1S") && warning.getMessage().contains("failure 1"), warning.getMessage());
        assertTrue(warning.getThrown() != null, "with the cause's stack trace");
        until(() -> !logged(Level.FINE).isEmpty(), "the DEBUG, written once the second failure is counted");
        assertEquals(1, logged(Level.FINE).size(), "the second failure is DEBUG: " + logged);
    }

    @Test
    void aConsumerTheComponentRefusesToBuildIsAFailedAttempt() {
        Inner c1 = inner().failStart("partner down");
        refusals.set(1);
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the first attempt failed");
        restarting.retry(start.cause());
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        waitsFor(Duration.ofSeconds(2), 2);
        assertEquals("the component refused the consumer", restarting.waiting().get().cause().getMessage());
        clock.advance(Duration.ofSeconds(2));
        runs(2);
    }

    @Test
    void aConsumerBuiltWithAnErrorOrAsNullIsAFailedAttempt() {
        Inner c1 = inner().failStart("partner down");
        builds.add(() -> { throw new NoClassDefFoundError("dev/partner/Client"); });
        builds.add(() -> null);
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the first attempt failed");
        restarting.retry(start.cause());
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        waitsFor(Duration.ofSeconds(2), 2);
        assertInstanceOf(NoClassDefFoundError.class, restarting.waiting().get().cause(), "an Error of the build fails the attempt");
        clock.advance(Duration.ofSeconds(2));
        waitsFor(Duration.ofSeconds(4), 3);
        assertInstanceOf(NullPointerException.class, restarting.waiting().get().cause(), "a build that answers null fails the attempt");
        clock.advance(Duration.ofSeconds(4));
        runs(2);
        Future<Void> stop = restarting.stop();
        until(stop::isComplete, "the stop completes: no attempt is left hanging");
    }

    @Test
    void retryAfterTheStopDoesNothingAndARetryWhileItRunsIsRefused() {
        Inner c1 = inner();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        assertThrows(IllegalStateException.class, () -> restarting.retry(new IllegalStateException("x")), "it runs: nothing to retry");
        Future<Void> stop = restarting.stop();
        until(stop::isComplete, "stopped");
        restarting.retry(new IllegalStateException("late"));
        assertEquals(0, timer.pending(), "nothing scheduled after the stop");
    }

    // --- a break

    @Test
    void aBreakStopsTheConsumerAndAFreshOneStartsAfterOneSecond() {
        Inner c1 = inner();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        c1.breakNow("the connection dropped");
        waitsFor(Duration.ofSeconds(1), 1);
        assertEquals(1, c1.stops.get(), "the broken consumer is stopped");
        assertEquals("the connection dropped", restarting.waiting().get().cause().getMessage());
        clock.advance(Duration.ofMillis(999));
        assertEquals(1, built.size(), "not before the delay: " + log);
        clock.advance(Duration.ofMillis(1));
        runs(2);
        assertEquals(List.of("build c1", "start c1", "stop c1", "stopped c1", "build c2", "start c2"), log);
        assertTrue(logged(Level.WARNING).getFirst().getMessage().contains("stopped working"), "" + logged);
    }

    @Test
    void aBreakBeforeTheStartCompletedCountsRightAfterIt() {
        Inner c1 = inner();
        c1.breakInsideStart = true;
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the start completes");
        assertTrue(start.succeeded(), "the start itself succeeded");
        waitsFor(Duration.ofSeconds(1), 1);
        assertEquals(1, c1.stops.get(), "the break was read once the start succeeded: stopped");
        clock.advance(Duration.ofSeconds(1));
        runs(2);
    }

    @Test
    void aShortRunContinuesTheCountAThirtySecondRunStartsItAgain() {
        Inner c1 = inner().failStart("partner down");
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "the first attempt failed");
        restarting.retry(start.cause());
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        runs(2);
        clock.advance(Duration.ofSeconds(29));
        c(2).breakNow("dropped after 29 s");
        waitsFor(Duration.ofSeconds(2), 2);
        clock.advance(Duration.ofSeconds(2));
        runs(3);
        clock.advance(Duration.ofSeconds(30));
        c(3).breakNow("dropped after 30 s");
        waitsFor(Duration.ofSeconds(1), 1);
    }

    @Test
    void aBreakAfterTheStopIsIgnored() {
        Inner c1 = inner();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        Future<Void> stop = restarting.stop();
        until(stop::isComplete, "stopped");
        c1.breakNow("closed by its own stop");
        clock.advance(Duration.ofHours(1));
        assertEquals(1, built.size(), "nothing built: " + log);
        assertEquals(1, c1.stops.get(), "stopped once");
        assertTrue(restarting.waiting().isEmpty());
    }

    // --- the stop

    @Test
    void aStopWhileWaitingCancelsTheNextAttempt() {
        Inner c1 = inner();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        c1.breakNow("dropped");
        waitsFor(Duration.ofSeconds(1), 1);
        Future<Void> stop = restarting.stop();
        until(stop::isComplete, "the stop completes at once: nothing runs");
        assertEquals(0, timer.pending(), "the next attempt was cancelled");
        assertTrue(restarting.waiting().isEmpty(), "no longer waiting once stopped");
        clock.advance(Duration.ofHours(1));
        assertEquals(1, built.size(), "nothing built after the stop: " + log);
    }

    @Test
    void aStopDuringAnAttemptWaitsForItAndStopsWhatItStarted() {
        Inner c1 = inner();
        next(Inner::holdStart);
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        c1.breakNow("dropped");
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        startedAttempt(2);
        Future<Void> stop = restarting.stop();
        assertFalse(stop.isComplete(), "the attempt in flight is awaited");
        c(2).heldStart.complete();
        until(stop::isComplete, "the stop completes once the attempt ended");
        assertEquals(1, c(2).stops.get(), "what the attempt started was stopped");
        assertEquals(0, timer.pending());
    }

    @Test
    void theStopWaitsForTheStopOfAConsumerItGaveUp() {
        Inner c1 = inner().holdStop();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        c1.breakNow("dropped");
        waitsFor(Duration.ofSeconds(1), 1);
        Future<Void> stop = restarting.stop();
        assertFalse(stop.isComplete(), "the broken consumer's stop has not settled");
        c1.heldStop.complete();
        until(stop::isComplete, "completes once it did");
    }

    @Test
    void aStopThatComesAsABrokenConsumerIsGivenUpWaitsForItsStop() {
        Inner c1 = inner().holdStop();
        java.util.concurrent.atomic.AtomicReference<Future<Void>> stop = new java.util.concurrent.atomic.AtomicReference<>();
        c1.onStop = () -> stop.set(restarting.stop());   // the node's stop, called just as the broken c1 is stopped
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        c1.breakNow("dropped");
        until(() -> stop.get() != null, "the stop came as c1 was given up; log " + log);
        assertFalse(stop.get().isComplete(), "the broken consumer's stop has not settled");
        c1.heldStop.complete();
        until(stop.get()::isComplete, "completes once it did");
        assertEquals(0, timer.pending(), "no attempt after the stop");
    }

    // --- never two at once

    @Test
    void aPollingConsumerStartsOnlyOnceTheOneBeforeItStopped() {
        Inner c1 = inner().holdStop();
        Future<Void> start = restarting(c1, true).start();
        until(start::isComplete, "it runs");
        c1.breakNow("the session dropped");
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        until(() -> timer.pending() == 1, "the attempt waits for c1's stop, bounded on the timer for its WARNING");
        clock.advance(BOUND);
        until(() -> logged(Level.WARNING).stream().anyMatch(r -> r.getMessage().contains("starts once it did")), "a WARNING at the bound: " + logged);
        clock.advance(Duration.ofHours(1));
        assertEquals(1, built.size(), "no second consumer while the first has not stopped: " + log);
        c1.heldStop.complete();
        runs(2);
        assertEquals(List.of("build c1", "start c1", "stop c1", "stopped c1", "build c2", "start c2"), log);
    }

    @Test
    void anyOtherConsumerWaitsForTheOneBeforeItAtMostTheBound() {
        Inner c1 = inner().holdStop();
        Future<Void> start = restarting(c1, false).start();
        until(start::isComplete, "it runs");
        c1.breakNow("the listener closed");
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        until(() -> timer.pending() == 1, "the attempt waits for c1's stop, at most the bound");
        clock.advance(BOUND.minusMillis(1));
        assertEquals(1, built.size(), "not before the bound: " + log);
        clock.advance(Duration.ofMillis(1));
        runs(2);
        assertTrue(logged(Level.WARNING).stream().anyMatch(r -> r.getMessage().contains("starts all the same")), "" + logged);
    }

    @Test
    void theAttemptAfterAStartThatOutlastedTheBoundWaitsForItsLateStop() {
        Inner c1 = inner().holdStart();
        Future<Void> start = restarting(c1, true).start();
        until(() -> c1.starts.get() == 1 && timer.pending() == 1, "the start is bounded");
        clock.advance(BOUND);
        until(start::isComplete, "the attempt failed at the bound");
        restarting.retry(start.cause());
        waitsFor(Duration.ofSeconds(1), 1);
        clock.advance(Duration.ofSeconds(1));
        until(() -> timer.pending() == 1, "the attempt waits for c1");
        clock.advance(Duration.ofMinutes(5));
        assertEquals(1, built.size(), "c1 has not stopped: " + log);
        c1.heldStart.complete();
        runs(2);
        assertEquals(1, c1.stops.get(), "c1 stopped once its late start completed, before c2 was built");
        assertTrue(log.indexOf("stopped c1") < log.indexOf("build c2"), "" + log);
    }

    @Test
    void theDelaysAreOneTwoFourEightSixteenThenThirtySeconds() {
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L),
                java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(n -> RestartingConsumer.delay(n).toSeconds()).toList());
        assertEquals(Duration.ofSeconds(30), RestartingConsumer.delay(Integer.MAX_VALUE), "no overflow, however long it fails");
    }
}
