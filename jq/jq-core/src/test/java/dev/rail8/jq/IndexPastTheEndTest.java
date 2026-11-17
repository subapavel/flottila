package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.steps;
import static dev.rail8.jq.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * ADR-0034: {@code .[n]} on a read array charges the elements it steps over, and an index past the end steps over all
 * of them and no further, however far past the end it is. {@code .[25]} over 20 elements: 1 read tick (a document
 * under 1 KiB) and 20 walk ticks, so it completes with 21 steps and ends with 20; jq-ref 1.8.2 gives null
 * ({@code [range(20)] | .[25]}, 2026-11-14). NavigationBudgetTest covers {@code .[20]}, where the index and the end
 * stop the walk at once.
 */
class IndexPastTheEndTest {
  private static final String TWENTY = "[" + "1,".repeat(19) + "1]";

  @Test
  void anIndexFarPastTheEndStepsOverEveryElementAndNoFurther() {
    JqEngine exact = steps(21);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, TWENTY).root();
    Results past = new Results();
    assertThat(exact.compile(".[25]").run(root, NONE, past, whole)).as("run's answer for .[25] over 20 elements with 21 steps").isTrue();
    assertThat(past.values).as("results of .[25] over 20 elements").containsExactly(JqNull.NULL);
    JqEngine under = steps(20);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, TWENTY).root();
    assertBudget(() -> under.compile(".[25]").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, ".[25] over 20 elements with 20 steps");
  }
}
