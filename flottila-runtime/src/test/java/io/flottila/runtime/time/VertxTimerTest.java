package io.flottila.runtime.time;

import io.flottila.api.Timer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runtime's Timer is a Vert.x timer: fires once on an event loop, cancellable while pending. */
class VertxTimerTest {

    final Vertx vertx = Vertx.vertx();
    final Timer timer = new VertxTimer(vertx);

    @AfterEach void close() { vertx.close().await(); }

    @Test
    void firesOnceAfterTheDelayOnAVertxThread() throws Exception {
        java.util.concurrent.atomic.AtomicInteger firings = new java.util.concurrent.atomic.AtomicInteger();
        Promise<String> fired = Promise.promise();
        Promise<Void> later = Promise.promise();
        timer.schedule(Duration.ofMillis(10), () -> { firings.incrementAndGet(); fired.tryComplete(Thread.currentThread().getName()); });
        timer.schedule(Duration.ofMillis(80), later::complete);
        String thread = fired.future().await(5, TimeUnit.SECONDS);
        assertTrue(thread.startsWith("vert.x-eventloop-thread"), thread);
        later.future().await(5, TimeUnit.SECONDS);
        assertEquals(1, firings.get(), "a one-shot timer fired exactly once within a later timer's window");
    }

    @Test
    void cancelStopsAPendingTaskAndReportsIt() throws Exception {
        CopyOnWriteArrayList<String> fired = new CopyOnWriteArrayList<>();
        Promise<Void> later = Promise.promise();
        Timer.Cancellable first = timer.schedule(Duration.ofMillis(20), () -> fired.add("cancelled"));
        timer.schedule(Duration.ofMillis(60), () -> { fired.add("later"); later.complete(); });
        assertTrue(first.cancel());
        assertFalse(first.cancel(), "already cancelled");
        later.future().await(5, TimeUnit.SECONDS);
        assertEquals(java.util.List.of("later"), fired, "the cancelled task never ran, the later one did");
    }

    @Test
    void zeroDelayIsAllowedAndNegativeIsNot() throws Exception {
        Promise<Void> fired = Promise.promise();
        timer.schedule(Duration.ZERO, fired::complete);
        fired.future().await(5, TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> timer.schedule(Duration.ofMillis(-1), () -> { }));
    }
}
