package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.value.JqNull;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A long comma chain compiles and runs without a StackOverflowError (#81's hand-back, #63 acceptance): chains of 10⁴
 * and 10⁵ ones, built through JqEngine since a command line over 128 KiB meets E2BIG, on the test thread and on a
 * 512 KiB thread (ADR-0017's {@code stackBytes}). How the implementer keeps the depth down (a balanced spine or
 * iterative walks) is free; the results and the ticks are not: every one in order, and one step per leaf but the first
 * (F-32, ADR-0034). jq-ref 1.8.2 prints the 10 922 ones of depth/comma-10921 and rejects longer chains with its own
 * bytecode limit (DEVIATIONS "Long comma chains").
 */
class CommaChainTest {

  /** ADR-0017's default stack for a run, the size #81 measures its parse depth against. */
  private static final long STACK = 512 * 1024;

  /** {@code 1,1,…,1} with {@code n} ones: n - 1 commas. */
  private static String chain(int n) {
    return "1" + ",1".repeat(n - 1);
  }

  /** The number of results of {@code filter} on null under {@code engine}, every one checked to be 1. */
  private static long count(JqEngine engine, String filter) {
    long[] count = {0};
    boolean ranOut =
        engine
            .compile(filter)
            .run(
                JqNull.NULL,
                NONE,
                v -> {
                  assertThat(v.toJson()).as("result %d", count[0]).isEqualTo("1");
                  count[0]++;
                  return true;
                },
                engine.newWorkspace());
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return count[0];
  }

  /** The checks of one chain: all its ones unbounded, with n - 1 steps, and the end with n - 2 steps. */
  private static void checks(int n) {
    String filter = chain(n);
    assertThat(count(unlimited(), filter)).as("results of a chain of %d ones", n).isEqualTo(n);
    assertThat(count(steps(n - 1), filter)).as("results of a chain of %d ones with %d steps", n, n - 1).isEqualTo(n);
    JqEngine under = steps(n - 2);
    assertBudget(() -> count(under, filter), Budget.STEPS, "a chain of " + n + " ones with " + (n - 2) + " steps");
  }

  @ParameterizedTest(name = "{0} ones on the test thread")
  @ValueSource(ints = {10_000, 100_000})
  void longChainRunsOnTheTestThread(int n) {
    checks(n);
  }

  @ParameterizedTest(name = "{0} ones on a 512 KiB thread")
  @ValueSource(ints = {10_000, 100_000})
  void longChainRunsOnA512KiBThread(int n) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread small = new Thread(null, () -> {
      try {
        checks(n);
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "512 KiB", STACK);
    small.start();
    small.join();
    assertThat(failure.get()).as("what a chain of %d ones threw on a 512 KiB thread", n).isNull();
  }
}
