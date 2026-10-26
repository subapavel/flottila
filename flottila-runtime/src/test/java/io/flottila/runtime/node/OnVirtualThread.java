package io.flottila.runtime.node;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a blocking node call on a virtual thread of its own, the way {@code Node.start} and {@code Node.close} are called
 * (D-057): never on the test's platform thread. A call still running after its bound fails the case and names where the
 * virtual thread sits.
 */
public final class OnVirtualThread {

    /** How long a node call may take before the case fails. */
    public static final Duration BOUND = Duration.ofSeconds(60);

    /** A call whose outcome {@link #join} reads; its thread is kept for the stack a timeout reports. */
    public record Call<T>(Thread thread, CompletableFuture<T> outcome) {
        public boolean isDone() { return outcome.isDone(); }
    }

    /** Something to run that may throw anything. */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    private OnVirtualThread() {}

    /** Starts {@code call} on a fresh virtual thread and returns at once. */
    public static <T> Call<T> start(Callable<T> call) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().name("node-test-call").start(() -> {
            try {
                outcome.complete(call.call());
            } catch (Throwable failed) {
                outcome.completeExceptionally(failed);
            }
        });
        return new Call<>(thread, outcome);
    }

    /** Starts {@code action} on a fresh virtual thread and returns at once. */
    public static Call<Void> start(Action action) {
        return start(() -> { action.run(); return null; });
    }

    /** Waits for {@code call} (at most {@link #BOUND}) and returns its result or rethrows what it threw, unwrapped. */
    public static <T> T join(Call<T> call) throws Exception {
        try {
            return call.outcome().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            if (e.getCause() instanceof Error x) throw x;
            throw e;
        } catch (TimeoutException stillRunning) {
            return fail("still running after " + BOUND + ", at " + Arrays.toString(call.thread().getStackTrace()));
        }
    }

    /** Runs {@code call} on a virtual thread and waits for it: its result, or what it threw. */
    public static <T> T call(Callable<T> call) throws Exception {
        return join(start(call));
    }
}
