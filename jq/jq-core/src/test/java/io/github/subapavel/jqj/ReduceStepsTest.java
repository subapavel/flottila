package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RangeStepsTest.completes;
import static io.github.subapavel.jqj.RangeStepsTest.ends;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * The cost rule of ADR-0041 at the public API: Reduce, Foreach and Advance neither tick nor charge; the loop back-edge
 * of ADR-0008 is the source's own producer (a Comma's right branch, F-32; range/2 per value, RangeStepsTest). So a
 * loop takes exactly the steps of its source and its updates. Each count has an adjacent pair: N steps complete, N - 1
 * end (ADR-0030). Every run is on {@code null} given as a value, so no read ticks. The results are jq-ref 1.8.2's,
 * observed 2026-11-13: {@code reduce (1,2,3) as $x (0; $x)} gives 3, {@code reduce range(0;1000) as $x (0; .)} and
 * {@code reduce range(0;1000) as $x (0; (., .))} give 0, {@code [foreach range(0;5) as $x (0; .)]} gives [0,0,0,0,0]
 * and {@code [foreach range(0;1000) as $x (0; .)] | length} 1000.
 */
class ReduceStepsTest {

  @Test
  void aReduceOverACommaOfThreeCostsTwoSteps() {
    // 1, 2, 3 enters a right branch twice; the reduce, its init and its update add nothing.
    assertThat(completes("reduce (1,2,3) as $x (0; $x)", 2)).as("results with 2 steps").containsExactly("3");
    assertThat(ends("reduce (1,2,3) as $x (0; $x)", 1)).as("results with 1 step before the end").isEmpty();
  }

  @Test
  void aReduceOverARangeCostsOneStepPerValue() {
    assertThat(completes("reduce range(0;1000) as $x (0; .)", 1000)).as("results with 1000 steps").containsExactly("0");
    assertThat(ends("reduce range(0;1000) as $x (0; .)", 999)).as("results with 999 steps before the end").isEmpty();
  }

  @Test
  void anUpdatesOwnStepsAddUpPerIteration() {
    // The update's comma enters its right branch once per value: 1000 + 1000.
    assertThat(completes("reduce range(0;1000) as $x (0; (., .))", 2000)).as("results with 2000 steps").containsExactly("0");
    assertThat(ends("reduce range(0;1000) as $x (0; (., .))", 1999)).as("results with 1999 steps before the end").isEmpty();
  }

  @Test
  void aCollectedForeachOverARangeCostsOneStepPerValue() {
    String zeros = "[" + String.join(",", Collections.nCopies(1000, "0")) + "]";
    assertThat(completes("[foreach range(0;1000) as $x (0; .)]", 1000)).as("results with 1000 steps").containsExactly(zeros);
    assertThat(ends("[foreach range(0;1000) as $x (0; .)]", 999)).as("results with 999 steps before the end").isEmpty();
  }

  @Test
  void aForeachEmitsEachStateBeforeTheNextTick() {
    assertThat(completes("foreach range(0;5) as $x (0; .)", 5)).as("results with 5 steps").containsExactly("0", "0", "0", "0", "0");
    assertThat(ends("foreach range(0;5) as $x (0; .)", 4)).as("results with 4 steps before the end").containsExactly("0", "0", "0", "0");
  }
}
