package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * range/2 is a Many (ADR-0041): one tick per value and nothing else, its bounds being constants here. A run may take
 * exactly its budget of ticks and the next one ends it with {@code JqTermination(BUDGET, STEPS)} (ADR-0030), so each
 * count has an adjacent pair: N steps complete, N - 1 end, and the tick comes before its value's emit. Every run is on
 * {@code null} given as a value, so no read ticks. The results are jq-ref 1.8.2's, observed 2026-11-13: {@code
 * [range(0;5)]} gives [0,1,2,3,4], {@code [range(0;0)]} [], {@code [range(0.5;3)]} [0.5,1.5,2.5] (reduce/
 * range2-fractional-start) and {@code [range(0;10)]} [0,…,9] (reduce/range2-ten).
 */
class RangeStepsTest {

  /** The result texts of {@code filter} on null with {@code steps} steps; asserts that the run completes. */
  static List<String> completes(String filter, long steps) {
    JqEngine engine = steps(steps);
    Results all = new Results();
    assertThat(engine.compile(filter).run(JqNull.NULL, NONE, all, engine.newWorkspace())).as("run's answer for %s with %s steps", filter, steps).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  /** The result texts of {@code filter} on null before it ends with STEPS under {@code steps} steps. */
  static List<String> ends(String filter, long steps) {
    JqEngine engine = steps(steps);
    Results some = new Results();
    assertBudget(() -> engine.compile(filter).run(JqNull.NULL, NONE, some, engine.newWorkspace()), Budget.STEPS, filter + " with " + steps + " steps");
    return some.values.stream().map(JqValue::toJson).toList();
  }

  @Test
  void fiveValuesCostFiveSteps() {
    assertThat(completes("range(0;5)", 5)).as("results of range(0;5) with 5 steps").containsExactly("0", "1", "2", "3", "4");
    assertThat(ends("range(0;5)", 4)).as("results of range(0;5) with 4 steps before the end").containsExactly("0", "1", "2", "3");
  }

  @Test
  void aThousandValuesCostAThousandSteps() {
    List<String> all = completes("range(0;1000)", 1000);
    assertThat(all).as("results of range(0;1000) with 1000 steps").hasSize(1000);
    assertThat(all.subList(0, 10)).as("the first results of range(0;1000)").containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertThat(all.getLast()).as("the last result of range(0;1000)").isEqualTo("999");
    assertThat(ends("range(0;1000)", 999)).as("results of range(0;1000) with 999 steps before the end").hasSize(999);
  }

  @Test
  void anEmptyRangeCostsNothing() {
    assertThat(completes("range(0;0)", 0)).as("results of range(0;0) with 0 steps").isEmpty();
    assertThat(completes("range(3;1)", 0)).as("results of range(3;1) with 0 steps").isEmpty();
    // Witness: a budget of 0 steps is live; one value ends the run.
    assertThat(ends("range(0;1)", 0)).as("results of range(0;1) with 0 steps").isEmpty();
  }

  @Test
  void fractionalBoundsCostOneStepPerValue() {
    assertThat(completes("range(0.5;3)", 3)).as("results of range(0.5;3) with 3 steps").containsExactly("0.5", "1.5", "2.5");
    assertThat(ends("range(0.5;3)", 2)).as("results of range(0.5;3) with 2 steps before the end").containsExactly("0.5", "1.5");
  }
}
