package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * String interpolation's charges at the public API (ADR-0038, ADR-0034 and ADR-0035 amendments): the dumps and the copy
 * are loops as long as the result, so it ticks once per started KiB of the result, as a concatenation does, and it
 * charges its string, STRING (40 B) plus the result's UTF-8 length, before it allocates. Each pin is an adjacent pair:
 * the run completes under N and ends under N - 1 with {@code JqTermination(BUDGET, ...)}. The strings come from {@code
 * "x" * n}, which folds at parse time (at most 4 KiB, ADR-0035 amendment), so the run charges only the interpolation;
 * the runs are on null, so no read ticks. The results are jq-ref 1.8.2's, observed 2026-11-13: {@code "x\(1)y" |
 * length} is 3 and {@code "<\(.)>"} on {@code "ab"} is {@code "<ab>"}; a string part is its own text
 * (diff/interpolation.rec).
 */
class InterpolationBudgetTest {

  @Test
  void anInterpolationTicksOncePerStartedKibOfItsResult() {
    // 1025 bytes are two started KiB, 2049 bytes three.
    assertSteps("(\"x\" * 1025) as $s | \"\\($s)\"", 2, quoted("x".repeat(1025)));
    assertSteps("(\"x\" * 2049) as $s | \"\\($s)\"", 3, quoted("x".repeat(2049)));
  }

  @Test
  void anInterpolationChargesAStringAndItsLength() {
    // "a1b" is 40 + 3; "<" + 1024 x "x" + ">" is 40 + 1026.
    assertBytes("\"a\\(1)b\"", 43, "\"a1b\"");
    assertBytes("(\"x\" * 1024) as $s | \"<\\($s)>\"", 1066, quoted("<" + "x".repeat(1024) + ">"));
  }

  private static String quoted(String text) {
    return "\"" + text + "\"";
  }

  /** {@code filter} on null completes with {@code limit} steps, giving {@code text}, and ends with one fewer. */
  private static void assertSteps(String filter, long limit, String text) {
    assertThat(results(steps(limit), filter)).as("results of %s with %d steps", filter, limit).containsExactly(text);
    JqEngine under = steps(limit - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(JqNull.NULL, NONE, none, under.newWorkspace()), Budget.STEPS, filter + " with " + (limit - 1) + " steps");
    assertThat(none.values).as("results of %s with %d steps", filter, limit - 1).isEmpty();
  }

  /** {@code filter} on null completes under {@code limit} bytes built, giving {@code text}, and ends under one fewer. */
  private static void assertBytes(String filter, long limit, String text) {
    assertThat(results(bytesBuilt(limit), filter)).as("results of %s under %d bytes built", filter, limit).containsExactly(text);
    JqEngine under = bytesBuilt(limit - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(JqNull.NULL, NONE, none, under.newWorkspace()), Budget.BYTES_BUILT, filter + " under " + (limit - 1) + " bytes built");
    assertThat(none.values).as("results of %s under %d bytes built", filter, limit - 1).isEmpty();
  }

  private static List<String> results(JqEngine engine, String filter) {
    Results results = new Results();
    assertThat(engine.compile(filter).run(JqNull.NULL, NONE, results, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }
}
