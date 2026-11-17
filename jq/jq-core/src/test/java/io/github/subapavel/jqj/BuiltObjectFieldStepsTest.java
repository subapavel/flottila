package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * A field of a built object charges one step per key it compares, up to the key that spells it, or every key when none
 * does (ADR-0034, #64): its keys are distinct, so the scan stops at the first match. The other tick sites are
 * NavigationBudgetTest's: a constant, a pipe and a construction charge no step, a run on a value no read made has no
 * read tick, and a field of a document's object charges every key it scans. Each case is an adjacent pair, N steps
 * complete and N - 1 end with {@code JqTermination(BUDGET, STEPS)} (ADR-0030). The expected results are jq-ref 1.8.2's,
 * observed 2026-11-13: {@code {a:1,b:2,c:3} | .a, .b, .c, .z} gives 1, 2, 3, null, and {@code {(.k):1,b:2} | .a, .b,
 * .z} on {"k":"a"} gives 1, 2, null.
 */
class BuiltObjectFieldStepsTest {
  /** The engine whose Workspace reads the input; a run never reads, so no run ticks for it. */
  private static final JqEngine READER = JqEngine.builder().build();

  @Test
  void aFieldOfAShapeObjectChargesTheKeysUpToItsOwn() {
    // On null, nothing but the field ticks: .a compares 1 key, .b 2, .c 3, and a missing .z all 3.
    assertExactSteps("{a:1,b:2,c:3} | .a", JqNull.NULL, 1, "1");
    assertExactSteps("{a:1,b:2,c:3} | .b", JqNull.NULL, 2, "2");
    assertExactSteps("{a:1,b:2,c:3} | .c", JqNull.NULL, 3, "3");
    assertExactSteps("{a:1,b:2,c:3} | .z", JqNull.NULL, 3, "null");
  }

  @Test
  void aFieldOfAGrowableObjectChargesTheKeysUpToItsOwn() {
    // The key .k is the document's string: its field scans the document's one key, 1 step; then the built object of
    // the keys "a" and "b" charges 1 for .a, 2 for .b and 2 for a missing .z.
    JqValue input = read(READER.newWorkspace(), "{\"k\":\"a\"}".getBytes(US_ASCII)).root();
    assertExactSteps("{(.k):1,b:2} | .a", input, 2, "1");
    assertExactSteps("{(.k):1,b:2} | .b", input, 3, "2");
    assertExactSteps("{(.k):1,b:2} | .z", input, 3, "null");
  }

  /**
   * The adjacent pair: {@code filter} on {@code input} completes with {@code steps} steps, giving {@code texts}, and
   * ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}, before it emits anything.
   */
  private static void assertExactSteps(String filter, JqValue input, long steps, String... texts) {
    JqEngine exact = steps(steps);
    Results all = new Results();
    assertThat(exact.compile(filter).run(input, NONE, all, exact.newWorkspace())).as("run's answer for %s with %s steps", filter, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of %s with %s steps", filter, steps).containsExactly(texts);
    JqEngine under = steps(steps - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(input, NONE, none, under.newWorkspace()), Budget.STEPS, filter + " with " + (steps - 1) + " steps");
    assertThat(none.values).as("results of %s with %s steps", filter, steps - 1).isEmpty();
  }
}
