package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.RandomParts.outcome;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.BindsPropertyTest.Case;
import io.github.subapavel.jqj.query.compile.RandomParts.Form;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Seeded property of the heap forms (ADR-0046 #109 amendment; docs/agents/environment.md, one case per seed): a run
 * declaring {@code stackBytes} 0, on the trampoline, ends as the same run in push form does, with the same results in
 * the same order, the same error or termination, the same answer to a sink that stops, and the same steps and bytes
 * built. The roots are {@link BindsPropertyTest}'s, in both of {@link RandomParts}' forms, so they hold Labels, Breaks
 * to an outer and an inner Label, Commas, Manys that raise after their last value, Collects, Ifs on tail and other
 * conditions, Binds of tail and other values, Binds, Pipes and raising and empty intrinsics, under a small step budget
 * in a third of the seeds and a sink that stops in two thirds. The push run declares {@link #PUSH}, and each seed runs
 * on a thread of four times that.
 */
class TrampolinePropertyTest {
  /**
   * The push run's declared stack: the roots hold no call, so they never reach its unit limit. It is above the reserve,
   * 280 KiB since #113, or the push run would start on the trampoline too.
   */
  private static final long PUSH = 512 * 1024;

  /** The thread of every seed: four times the push run's declared stack (ADR-0046 "Where the stack is tested"). */
  private static final long THREAD = 4 * PUSH;

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("io.github.subapavel.jqj.query.compile.BindsPropertyTest#seeds")
  void theTrampolineEndsAsThePushFormDoes(long seed) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                check(seed);
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "trampoline-" + seed,
            THREAD);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    if (failure.get() instanceof Error error) throw error;
    if (failure.get() instanceof RuntimeException runtime) throw runtime;
  }

  private static void check(long seed) {
    Case c = BindsPropertyTest.generate(seed);
    for (Form form : List.of(Form.tail(c.free()), Form.push(c.free()))) {
      for (CompileMode mode : CompileMode.values()) {
        Outcome pushed = outcome(c.root().build(form), mode, c.steps(), c.stopAt(), PUSH);
        assertThat(pushed.crashed()).as("seed %d, %s: whether the push form crashed (%s) on %s", seed, mode, pushed, c).isFalse();
        assertThat(outcome(c.root().build(form), mode, c.steps(), c.stopAt(), 0)).as("seed %d, %s: outcome on the trampoline of %s", seed, mode, c).isEqualTo(pushed);
      }
    }
  }

  /**
   * Witness that the property is not vacuous on the trampoline: over the seeds, its runs run out with two results or
   * more, run out with none, stop at the sink, raise and end at the step budget, each in 5 % of the seeds at least.
   */
  @Test
  void theSeedsReachEveryOutcomeOnTheTrampoline() {
    List<Outcome> outcomes = new ArrayList<>();
    BindsPropertyTest.seeds().forEach(seed -> {
      Case c = BindsPropertyTest.generate(seed);
      outcomes.add(outcome(c.root().build(Form.tail(c.free())), CompileMode.CONSERVATIVE, c.steps(), c.stopAt(), 0));
    });
    Map<String, Integer> kinds = RandomParts.kinds(outcomes);
    int least = outcomes.size() / 20;
    assertThat(kinds).as("outcomes on the trampoline by kind over %d seeds", outcomes.size()).doesNotContainKey("crash");
    for (String kind : List.of("results", "none", "stop", "error", "termination")) {
      assertThat(kinds.getOrDefault(kind, 0)).as("seeds whose trampoline run ends in %s, of %s", kind, kinds).isGreaterThanOrEqualTo(least);
    }
  }
}
