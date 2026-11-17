package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RangeStepsTest.completes;
import static io.github.subapavel.jqj.RangeStepsTest.ends;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * #69's acceptance: {@code reduce range(0; 1e9) as $x (0; .)} under a small step budget ends as {@code
 * JqTermination(BUDGET, STEPS)} (ADR-0008, ADR-0041), long before its billion values. jq-ref 1.8.2, observed
 * 2026-11-13: {@code reduce range(0;10000) as $x (0; .)} gives 0 (and reduce/range2-hundred-thousand gives 99999).
 */
class ReduceBudgetTest {

  @Test
  void aBillionValuesEndUnderTenThousandSteps() {
    assertThat(ends("reduce range(0; 1e9) as $x (0; .)", 10_000)).as("results of the billion-value reduce").isEmpty();
    assertThat(ends("[foreach range(0; 1e9) as $x (0; .)]", 10_000)).as("results of the billion-value foreach").isEmpty();
    // Witness: ten thousand values fit the same budget.
    assertThat(completes("reduce range(0; 10000) as $x (0; .)", 10_000)).as("results of the ten-thousand-value reduce").containsExactly("0");
  }
}
