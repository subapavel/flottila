package io.flottila.core.pattern;

import io.flottila.api.Envelope;
import io.flottila.api.Stage;
import io.flottila.api.Step;
import io.flottila.api.Timer;
import io.flottila.core.flow.RetryPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Retry per step (ARCHITECTURE §4): a step marked {@code retryable} is tried again after a failure, with the
 * flow's {@link RetryPolicy} backoff, driven by the injected {@link Timer} — the message's virtual thread parks
 * between attempts, never sleeps, and a fake clock drives it in tests. After the last attempt the last exception
 * is thrown with the earlier ones suppressed; the message fails (DLQ is the runtime's job). An interrupt while
 * waiting (a cancelled scope) stops retrying at once, keeps the interrupt and throws the last exception.
 *
 * <p>Only {@link RuntimeException}s are retried; an {@link Error} is not a transient failure.
 *
 * @since 0.1
 */
public final class Retry {

    private Retry() {}

    /** The step with the policy applied; the step itself when the policy has no retries. */
    public static Step step(Step step, RetryPolicy policy, Timer timer) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(timer, "timer");
        if (policy.attempts() == 0) return step;
        return e -> {
            List<RuntimeException> earlier = new ArrayList<>();
            for (int attempt = 1; ; attempt++) {
                try {
                    return step.apply(e);
                } catch (RuntimeException failure) {
                    if (attempt > policy.attempts() || !pause(policy.delayBefore(attempt), timer)) {
                        earlier.forEach(failure::addSuppressed);
                        throw failure;
                    }
                    earlier.add(failure);
                }
            }
        };
    }

    /**
     * The stage with the policy applied to its own work: what it hands down is collected while it runs and passed
     * to the rest of the pipeline once it has succeeded, so a retry never re-runs the steps after it. With one
     * envelope handed down the rest's result is returned (the usual step); with none or several, the stage's own
     * (a filter's, a splitter's).
     */
    public static Stage stage(Stage stage, RetryPolicy policy, Timer timer) {
        Objects.requireNonNull(stage, "stage");
        if (policy.attempts() == 0) return stage;
        return (e, rest) -> {
            List<Envelope> handed = new ArrayList<>();
            Step own = x -> {
                handed.clear();
                return stage.apply(x, h -> { handed.add(h); return h; });
            };
            Envelope result = step(own, policy, timer).apply(e);
            Envelope last = null;
            for (Envelope h : handed) last = rest.apply(h);
            return handed.size() == 1 ? last : result;
        };
    }

    /** Parks until the timer fires; false when interrupted (the interrupt is kept, the timer task cancelled). */
    private static boolean pause(java.time.Duration delay, Timer timer) {
        CountDownLatch fired = new CountDownLatch(1);
        Timer.Cancellable scheduled = timer.schedule(delay, fired::countDown);
        try {
            fired.await();
            return true;
        } catch (InterruptedException interrupted) {
            scheduled.cancel();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
