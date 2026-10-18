package io.flottila.core.pattern;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.core.flow.RetryPolicy;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Retry over a Stage: the stage's own work is retried, what it hands down runs once, after it succeeded. */
@Timeout(60)
class RetryStageTest {

    final FakeRuntime rt = FakeRuntime.create();
    final Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<a/>"));
    final RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(10), 2.0);

    Envelope drive(Stage stage, List<Envelope> downstream) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { out.set(stage.apply(in, e -> { downstream.add(e); return e.withHeader("rest", "ran"); })); } catch (Throwable x) { out.set(x); } });
        while (t.isAlive()) { if (rt.timer().pending() > 0) rt.clock().advance(rt.timer().nextDelay()); Thread.onSpinWait(); Thread.yield(); }
        if (out.get() instanceof RuntimeException e) throw e;
        return (Envelope) out.get();
    }

    @Test
    void theRestRunsOnceAfterTheRetriedStepSucceededAndItsResultIsReturned() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Stage flaky = Stage.of(e -> { if (calls.incrementAndGet() < 3) throw new IllegalStateException("try " + calls.get()); return e.withHeader("own", "done"); });
        List<Envelope> downstream = new ArrayList<>();
        Envelope out = drive(Retry.stage(flaky, policy, rt.timer()), downstream);
        assertEquals(3, calls.get());
        assertEquals(1, downstream.size(), "the rest ran once, not once per attempt");
        assertEquals("done", downstream.get(0).header("own").orElseThrow());
        assertEquals("ran", out.header("rest").orElseThrow(), "with one envelope handed down the rest's result is the stage's result");
    }

    @Test
    void aFailureOfTheRestIsNotRetriedAndAFilterThatHandsNothingDownReturnsItsOwnResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Stage counted = Stage.of(e -> { calls.incrementAndGet(); return e; });
        Stage retried = Retry.stage(counted, policy, rt.timer());
        assertThrows(IllegalStateException.class, () -> retried.apply(in, e -> { throw new IllegalStateException("downstream"); }));
        assertEquals(1, calls.get(), "the rest's failure surfaces at once; the step is not re-run");
        assertEquals(0, rt.timer().pending());
        Envelope kept = in.withHeader("filtered", "yes");
        List<Envelope> downstream = new ArrayList<>();
        assertSame(kept, drive(Retry.stage((e, rest) -> kept, policy, rt.timer()), downstream));
        assertEquals(List.of(), downstream);
    }

    @Test
    void aStageThatHandsDownSeveralEnvelopesSeesTheRestRunForEachAndReturnsItsOwnResult() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Stage fanOut = (e, rest) -> {
            if (attempts.incrementAndGet() == 1) { rest.apply(e.withHeader("child", "0")); throw new IllegalStateException("first"); }
            rest.apply(e.withHeader("child", "1"));
            rest.apply(e.withHeader("child", "2"));
            return e;
        };
        List<Envelope> downstream = new ArrayList<>();
        assertSame(in, drive(Retry.stage(fanOut, policy, rt.timer()), downstream));
        assertEquals(List.of("1", "2"), downstream.stream().map(e -> e.header("child").orElseThrow()).toList(), "the first attempt's children were discarded with it");
        assertSame(fanOut, Retry.stage(fanOut, RetryPolicy.NONE, rt.timer()), "no retries: the stage itself");
    }
}
