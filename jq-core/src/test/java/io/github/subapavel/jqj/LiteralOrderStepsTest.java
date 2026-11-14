package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * The number ticks of the value order (ADR-0037 "one per started KiB ... of a literal on the BigDecimal path", pinned
 * by the implementer as the #66 red approval's ruling 2 asks): two literals that are not both longs and not both short
 * compare exactly, reading each token whole, so the order charges one tick per started KiB of each token; two longs,
 * two short literals and a literal against a computed number charge nothing, and neither does Equality's compare of
 * the same tokens (ADR-0034 amendment for #65). Each filter wraps its numbers in arrays, so the one element pair is a
 * tick of its own (ComparisonStepsTest); each count is an adjacent pair, N steps complete and N - 1 end with
 * {@code JqTermination(BUDGET, STEPS)}. A token {@code 1.000...} is not canonical, so never short. Every expected result
 * is jq-ref 1.8.2's, observed 2026-11-13 with {@code jq-ref -nc} on the same filter: false for each order below but
 * {@code [1.5] < [2.5]}, true, and true for the {@code ==}.
 */
class LiteralOrderStepsTest {
  /** {@code 1.} and {@code zeros} zeros: a literal of {@code zeros + 2} bytes equal to 1. */
  private static String one(int zeros) {
    return "1." + "0".repeat(zeros);
  }

  @Test
  void anExactCompareChargesOneStepPerStartedKiBOfEachToken() {
    // 1024 bytes start one KiB, and 1 one KiB: the pair and two.
    assertExactSteps("[" + one(1022) + "] < [1]", 3, "false");
    // 1025 bytes start two.
    assertExactSteps("[" + one(1023) + "] < [1]", 4, "false");
    assertExactSteps("[" + one(1023) + "] < [" + one(1023) + "]", 5, "false");
  }

  @Test
  void theLongAndDoublePathsChargeNothing() {
    // 18 digits are no short token, but both fit a long.
    assertExactSteps("[100000000000000000] < [2]", 1, "false");
    assertExactSteps("[1.5] < [2.5]", 1, "true");
    // 1 + 0 folds to a computed number, so the pair compares as doubles.
    assertExactSteps("[" + one(1023) + "] > [1 + 0]", 1, "false");
  }

  @Test
  void equalityOfTheSameTokensChargesOnlyItsPair() {
    assertExactSteps("[" + one(1023) + "] == [1]", 1, "true");
  }

  /**
   * The adjacent pair: {@code filter} on null completes with {@code steps} steps, giving {@code text}, and ends with
   * {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertExactSteps(String filter, long steps, String text) {
    String what = filter.length() > 60 ? filter.substring(0, 30) + "…" + filter.substring(filter.length() - 20) : filter;
    JqEngine exact = steps(steps);
    Results all = new Results();
    assertThat(exact.compile(filter).run(JqNull.NULL, NONE, all, exact.newWorkspace())).as("run's answer for %s with %s steps", what, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of %s with %s steps", what, steps).containsExactly(text);
    JqEngine under = steps(steps - 1);
    assertBudget(() -> under.compile(filter).run(JqNull.NULL, NONE, new Results(), under.newWorkspace()), Budget.STEPS, what + " with " + (steps - 1) + " steps");
  }
}
