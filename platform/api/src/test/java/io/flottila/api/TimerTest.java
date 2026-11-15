package io.flottila.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timer is the injected one-shot scheduler (CLAUDE.md: clock, timers, ids through Runtime; no static singletons). */
class TimerTest {

    @Test
    void aTimerIsAFunctionalInterfaceReturningACancellableHandle() {
        List<Runnable> scheduled = new ArrayList<>();
        Timer timer = (delay, task) -> { scheduled.add(task); return () -> scheduled.remove(task); };
        Timer.Cancellable handle = timer.schedule(Duration.ofMillis(5), () -> { });
        assertEquals(1, scheduled.size());
        assertTrue(handle.cancel());
        assertEquals(0, scheduled.size());
    }
}
