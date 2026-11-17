package dev.rail8.jq.oracle;

import dev.rail8.jq.Limits;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * The one fixed thread every oracle runner runs its cases on (F-88, ADR-0046 #111 amendment): the differential and
 * json runners' entry, the jqtest runner's compile and run, and each run of the compile-mode oracle. Its stack is
 * {@code max(4 × Limits.cli().stackBytes(), 2 MiB)}: an in-suite run gets at least four times the declared stack, and
 * the floor covers the trampoline run's {@code stackBytes} 0. No case sets {@code stackBytes}. It lives with the tests
 * because the oracle module does not read jq-core.
 */
public final class RunnerThread {

  /** The thread's stack in bytes. */
  public static final long STACK_BYTES = Math.max(4 * Limits.cli().stackBytes(), 2L << 20);

  private static final ExecutorService THREAD =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(null, r, "oracle-runner", STACK_BYTES);
            t.setDaemon(true);
            return t;
          });

  private RunnerThread() {}

  /**
   * What {@code work} gives on the runner thread, waiting for it; what it throws is thrown here, an error or a runtime
   * exception as it is.
   */
  public static <T> T call(Supplier<T> work) {
    Future<T> done = THREAD.submit(work::get);
    try {
      return done.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while the runner thread ran a case", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Error error) throw error;
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(cause);
    }
  }

  /** {@code work} on the runner thread, as {@link #call}. */
  public static void run(Runnable work) {
    call(
        () -> {
          work.run();
          return null;
        });
  }
}
