package io.flottila.cli;

import io.flottila.api.Ack;
import io.flottila.api.Timer;
import io.flottila.components.mock.MockComponent;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.flottila.cli.QuiescenceTest.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b1 fix (F-17): what the runner reads of a case's world — the fingerprint {@link Quiescence} compares — moves with every
 * timer call and every clock move, a zero advance too, and a thread inside a timer call is busy; whether the case's answer is
 * done is read without the answer's monitor (review r1: Vert.x 5.1.10 {@code FutureImpl.isComplete()} is {@code synchronized}).
 *
 * <p>The seam these cases define (package-private in {@code io.flottila.cli}; today a lambda and a private class inside
 * {@code FlowTests.play}):
 * <ul>
 *   <li>{@code FlowTests.Watched} — {@code new Watched()}, {@code Timer timer(Timer)} (the case's timer, its schedules and
 *       cancels counted as they return), {@code int inside()} (the threads inside one of those calls);</li>
 *   <li>{@code static Supplier<List<Object>> FlowTests.world(Watched watched, FakeClock clock, MockComponent door, String from,
 *       Collection<MockComponent> mocks, BooleanSupplier done)} — the fingerprint {@code play} hands to {@link Quiescence}: the
 *       watched calls, the clock's moves (counted from here on, {@code clock.onChange}), its instant, the door's deliveries on
 *       {@code from}, each mock's sends, and {@code done} (the case's answer in {@code play}, the node's close in {@code close});</li>
 *   <li>{@code static BooleanSupplier FlowTests.done(Future<?> future)} — whether {@code future} has completed (succeeded or
 *       failed), read without taking its monitor: a flag a listener registered at this call sets. {@code play} reads the
 *       answer through it only, in the fingerprint and in the settle check.</li>
 * </ul>
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FlowTestsWorldTest {

    private final FakeRuntime rt = FakeRuntime.create();
    private final FlowTests.Watched watched = new FlowTests.Watched();
    private final Timer timer = watched.timer(rt.timer());
    private final MockComponent door = new MockComponent("http", rt.blobs(), timer, rt.ids());
    private final Supplier<List<Object>> world = FlowTests.world(watched, rt.clock(), door, "in", List.of(door), () -> false);

    /** Nothing happens: two reads are equal (the cases below see a change, not a fingerprint that differs on every read). */
    @Test
    void anUnchangedWorldReadsTheSame() {
        assertEquals(world.get(), world.get());
    }

    /** A schedule and a cancel each change the fingerprint, though the clock does not move. */
    @Test
    void aTimerCallChangesTheFingerprint() {
        List<Object> before = world.get();
        var standing = rt.clock().instant();

        Timer.Cancellable scheduled = timer.schedule(Duration.ofSeconds(1), () -> {});
        List<Object> afterSchedule = world.get();
        scheduled.cancel();
        List<Object> afterCancel = world.get();

        assertEquals(standing, rt.clock().instant(), "the clock never moved");
        assertNotEquals(before, afterSchedule, "a schedule");
        assertNotEquals(afterSchedule, afterCancel, "a cancel");
    }

    /** An advance of the clock changes the fingerprint — a zero one too (the tasks due now ran), where the instant stays. */
    @Test
    void aClockMoveChangesTheFingerprintAZeroOneToo() {
        List<Object> before = world.get();

        rt.clock().advance(Duration.ZERO);
        List<Object> afterZero = world.get();
        rt.clock().advance(Duration.ofMillis(1));
        List<Object> afterOne = world.get();

        assertNotEquals(before, afterZero, "a zero advance");
        assertNotEquals(afterZero, afterOne, "a 1 ms advance");
    }

    /**
     * A virtual thread blocked inside a timer call (the timer's monitor held, as the clock thread holds it firing due tasks) is
     * inside it — busy — and its call is not counted yet; once it returns, it is out and the call changed the fingerprint.
     */
    @Test
    void aThreadBlockedInATimerCallIsInsideItUntilItReturns() throws InterruptedException {
        List<Object> before = world.get();
        Thread scheduling;
        synchronized (rt.timer()) {
            scheduling = Thread.ofVirtual().name("scheduling").start(() -> timer.schedule(Duration.ofSeconds(1), () -> {}));
            until(() -> scheduling.getState() == Thread.State.BLOCKED, "the virtual thread blocks on the timer's monitor");

            assertEquals(1, watched.inside(), "blocked inside the call: busy");
            assertEquals(before, world.get(), "its call is counted as it returns, not before");
        }
        scheduling.join();

        assertEquals(0, watched.inside(), "returned: no longer inside");
        assertNotEquals(before, world.get(), "the returned call changed the fingerprint");
    }

    /** {@code done} of an answer that has already completed, succeeded or failed, is true at once; of a pending one, false. */
    @Test
    void doneIsTrueForACompletedAnswerSucceededOrFailed() {
        assertTrue(FlowTests.done(Future.succeededFuture(new Ack.Accepted("m-1"))).getAsBoolean(), "succeeded before");
        assertTrue(FlowTests.done(Future.failedFuture(new IllegalStateException("down"))).getAsBoolean(), "failed before");
        Promise<Ack> failing = Promise.promise();
        BooleanSupplier failed = FlowTests.done(failing.future());
        assertFalse(failed.getAsBoolean(), "pending");
        failing.fail(new IllegalStateException("down"));
        assertTrue(failed.getAsBoolean(), "failed after");
    }

    /**
     * Another thread holds the answer future's monitor — the monitor {@code isComplete()} takes (witnessed: a thread calling it
     * blocks) — and the runner's reads, the fingerprint and {@code done} the settle check asks, still return, the answer pending;
     * once the answer completes, {@code done} is true and the fingerprint has moved.
     */
    @Test
    void theAnswerIsReadWithoutItsMonitor() throws InterruptedException {
        Promise<Ack> promise = Promise.promise();
        Future<Ack> answer = promise.future();
        BooleanSupplier done = FlowTests.done(answer);
        Supplier<List<Object>> answered = FlowTests.world(watched, rt.clock(), door, "in", List.of(door), done);
        List<Object> before = answered.get();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().name("answer-monitor").start(() -> {
            synchronized (answer) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException("the monitor holder was interrupted", interrupted);
                }
            }
        });
        Thread witness = null;
        Thread reader = null;
        try {
            holding.await();
            witness = Thread.ofPlatform().name("isComplete").start(answer::isComplete);
            Thread blocked = witness;
            until(() -> blocked.getState() == Thread.State.BLOCKED, "witness: isComplete() blocks on the monitor the holder holds");

            AtomicReference<List<Object>> read = new AtomicReference<>();
            reader = Thread.ofPlatform().name("runner-read").start(() -> read.set(List.of(answered.get(), done.getAsBoolean())));
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (read.get() == null) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the runner's reads did not return within 10 s while another thread held the answer's monitor; "
                            + "the reader is " + reader.getState() + " at " + Arrays.toString(reader.getStackTrace()));
                }
                Thread.onSpinWait();
            }
            assertEquals(List.of(before, false), read.get(), "the answer pending: the same fingerprint, not done");
        } finally {
            release.countDown();
            holder.join();
            if (witness != null) witness.join();
            if (reader != null) reader.join();
        }

        promise.complete(new Ack.Accepted("m-1"));

        assertTrue(done.getAsBoolean(), "completed: done");
        assertNotEquals(before, answered.get(), "the answer's completion moved the fingerprint");
    }
}
