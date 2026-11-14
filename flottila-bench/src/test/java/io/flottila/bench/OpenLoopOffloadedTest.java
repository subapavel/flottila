package io.flottila.bench;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b, the implementer's cases on {@link OpenLoop#offloaded}: a run's samplers read the database and /proc, which must not
 * block the loop's timer (an event loop in a run). Each sample runs on a virtual thread, in order; the record waits until the
 * last one finished; a sample that throws fails the record instead of leaving a series short, and the samples queued after it
 * never run. {@link OpenLoopSamplerTest}'s fake clock and timer; a hand-run executor shows what is handed over when.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OpenLoopOffloadedTest {

    /** A sampler that notes each sample's index and whether it ran on a virtual thread, blocking in sample {@code holdAt}. */
    static final class Held implements OpenLoop.Sampler {
        final List<String> ran = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final int holdAt;
        int taken;

        Held(int holdAt) {
            this.holdAt = holdAt;
        }

        @Override public void sample() {
            int k = taken++;
            ran.add(k + (Thread.currentThread().isVirtual() ? " virtual" : " platform"));
            if (k == holdAt) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }
        }

        @Override public JsonObject series() {
            return new JsonObject().put("ran", new ArrayList<>(ran));
        }
    }

    static JsonObject await(OpenLoop loop) throws Exception {
        return loop.done().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void eachSampleRunsOnAVirtualThreadInOrderAndTheRecordWaitsForTheLastOne() throws Exception {
        FakeClock clock = FakeClock.at(OpenLoopTest.START);
        FakeTimer timer = new FakeTimer(clock);
        Held held = new Held(1);   // the second sample blocks: the third queues behind it
        OpenLoop loop = OpenLoopSamplerTest.loop(clock, timer, 10, Duration.ZERO, Duration.ofSeconds(2), Map.of("db", OpenLoop.offloaded(held)));
        loop.start();
        OpenLoopSamplerTest.stepTo(clock, 2600);

        assertFalse(loop.done().isComplete(), "every request answered, every sample taken, but the second is still running");
        held.release.countDown();
        JsonObject open = await(loop);

        List<String> inOrder = List.of("0 virtual", "1 virtual", "2 virtual");
        assertEquals(inOrder, held.ran, "off the timer's thread, one after the other");
        assertEquals(new JsonObject().put("ran", inOrder), open.getJsonObject("db"), "the series once the last sample finished: " + open.encode());
    }

    @Test
    void aSampleThatThrowsFailsTheRecord() {
        FakeClock clock = FakeClock.at(OpenLoopTest.START);
        FakeTimer timer = new FakeTimer(clock);
        OpenLoop.Sampler broken = new OpenLoop.Sampler() {
            @Override public void sample() {
                throw new IllegalStateException("the sampler broke");
            }

            @Override public JsonObject series() {
                return new JsonObject();
            }
        };
        OpenLoop loop = OpenLoopSamplerTest.loop(clock, timer, 10, Duration.ZERO, Duration.ofSeconds(1), Map.of("db", OpenLoop.offloaded(broken)));
        loop.start();
        OpenLoopSamplerTest.stepTo(clock, 1600);

        ExecutionException failed = assertThrows(ExecutionException.class, () -> await(loop));
        boolean named = false;
        for (Throwable t = failed; t != null; t = t.getCause()) named |= "the sampler broke".equals(t.getMessage());
        assertTrue(named, "the record fails with the sampler's failure: " + failed);
    }

    @Test
    void anInlineSamplerSettlesAtOnce() {
        CompletableFuture<Void> settled = new OpenLoopSamplerTest.Noting(FakeClock.at(OpenLoopTest.START)).settled().toCompletionStage()
                .toCompletableFuture();
        assertTrue(settled.isDone() && !settled.isCompletedExceptionally(), "a sampler that samples in place has nothing in flight");
    }

    /** A sampler that logs each sample's start and end, and throws in sample {@code throwAt} (none: -1). */
    static final class Logged implements OpenLoop.Sampler {
        final List<String> events = new ArrayList<>();
        final int throwAt;
        int taken;

        Logged(int throwAt) {
            this.throwAt = throwAt;
        }

        @Override public void sample() {
            int k = taken++;
            events.add("start " + k);
            if (k == throwAt) throw new IllegalStateException("sample " + k + " broke");
            events.add("end " + k);
        }

        @Override public JsonObject series() {
            return new JsonObject();
        }
    }

    @Test
    void aSampleIsHandedOverOnlyOnceTheOneBeforeItEnded() {
        Deque<Runnable> handed = new ArrayDeque<>();
        Logged logged = new Logged(-1);
        OpenLoop.Sampler sampler = OpenLoop.offloaded(logged, handed::add);
        for (int k = 0; k < 3; k++) sampler.sample();   // the loop's timer queues three samples at once

        for (int k = 0; k < 3; k++) {
            assertEquals(1, handed.size(), "one sample handed over at a time, the next waits for its end: " + logged.events);
            handed.poll().run();
        }
        assertEquals(0, handed.size());
        assertEquals(List.of("start 0", "end 0", "start 1", "end 1", "start 2", "end 2"), logged.events, "each starts after the one before ended");
        assertTrue(sampler.settled().succeeded(), "the last one ended");
    }

    @Test
    void theSamplesQueuedAfterOneThatThrewNeverRun() {
        Deque<Runnable> handed = new ArrayDeque<>();
        Logged logged = new Logged(0);   // the first sample throws; the next two would succeed
        OpenLoop.Sampler sampler = OpenLoop.offloaded(logged, handed::add);
        for (int k = 0; k < 3; k++) sampler.sample();

        handed.poll().run();

        assertEquals(0, handed.size(), "nothing handed over after the throw: " + logged.events);
        assertEquals(List.of("start 0"), logged.events, "the series stops at the failure, it does not go on with a hole");
        assertTrue(sampler.settled().failed(), "settled fails with the sample's failure");
        assertEquals("sample 0 broke", rootCause(sampler.settled().cause()).getMessage());
    }

    static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }
}
