package dev.rail8.jq;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A child JVM that shows whether a virtual cell gives up its carrier while it waits for a compile on another thread.
 * It is started with one carrier thread ({@code -Djdk.virtualThreadScheduler.parallelism=1} and {@code
 * maxPoolSize=1}). A virtual cell starts a second virtual thread, the probe, which goes to the queue of the one
 * carrier, and then waits for a compile that runs on a platform thread. The probe can run only on that carrier, so it
 * runs during the wait only if the cell unmounts, whether it parks or yields. The compile holds the wait open until
 * the probe has run or for {@value #SPINS} spins, whichever comes first.
 *
 * <p>{@code args}: {@code helper} (the cell waits in {@link CalibrationCell#onHelper}), {@code yield} (it waits in a
 * {@code Thread.yield()} loop) or {@code park} (it waits in {@code join()}). Prints {@code <wait>: the probe ran while
 * the cell waited} or {@code <wait>: the probe waited for the cell}.
 */
final class HelperWaitChild {
  /** How long the compile holds the wait open for a probe that does not run, in {@code Thread.onSpinWait} calls. */
  static final long SPINS = 20_000_000;

  private HelperWaitChild() {}

  public static void main(String[] args) throws InterruptedException {
    String wait = args[0];
    AtomicBoolean probeRan = new AtomicBoolean();
    Supplier<String> compile =
        () -> {
          for (long i = 0; i < SPINS && !probeRan.get(); i++) Thread.onSpinWait();
          return probeRan.get() ? "the probe ran while the cell waited" : "the probe waited for the cell";
        };
    AtomicReference<Thread> probe = new AtomicReference<>();
    AtomicReference<String> result = new AtomicReference<>();
    Thread cell =
        Thread.ofVirtual()
            .start(
                () -> {
                  probe.set(Thread.ofVirtual().start(() -> probeRan.set(true)));
                  result.set(waitFor(wait, compile));
                });
    cell.join();
    probe.get().join();
    System.out.println(wait + ": " + result.get());
  }

  private static String waitFor(String wait, Supplier<String> compile) {
    return switch (wait) {
      case "helper" -> CalibrationCell.onHelper(compile);
      case "yield" -> {
        CompletableFuture<String> compiled = onPlatformThread(compile);
        while (!compiled.isDone()) Thread.yield();
        yield compiled.join();
      }
      case "park" -> onPlatformThread(compile).join();
      default -> throw new IllegalArgumentException("no wait " + wait);
    };
  }

  private static CompletableFuture<String> onPlatformThread(Supplier<String> compile) {
    return CompletableFuture.supplyAsync(compile, work -> Thread.ofPlatform().daemon().start(work));
  }
}
