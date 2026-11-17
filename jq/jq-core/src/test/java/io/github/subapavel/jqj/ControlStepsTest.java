package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cost rule of ADR-0039 at the public API (#68): If, Try (the handler entry included, O4), Label, Break and
 * {@code error} neither tick nor charge. So each control form takes exactly the steps of the same filter without it.
 * A run may take exactly its budget of ticks and the next one ends it with {@code JqTermination(BUDGET, STEPS)}
 * (ADR-0030), so each count has an adjacent pair: N steps complete, N - 1 end.
 *
 * <p>The counts come from the rules the witnesses already pin: a document under 1 KiB reads in one tick (ADR-0015,
 * StepBudgetTest), {@code .[]} charges one step per element before its emit (ADR-0034, NavigationBudgetTest), and
 * entering a Comma's right branch charges one (F-32, CommaChainTest). On 20 ones: 1 read tick, 20 element ticks and one
 * tick per element for its comma, 41 in all. With 40 steps the 41st tick is the comma of the 20th element, after that
 * element's left result. The results are jq-ref 1.8.2's, observed 2026-11-13 on the 20 ones for each filter here: 1, 2
 * per element for each form around {@code (1, 2)}, and 1 per element for {@code 1, empty} and {@code label $l | 1,
 * break $l}; {@code try error("x") catch .} on null gives {@code "x"}.
 */
class ControlStepsTest {

  /** {@code [1,1,…,1]} with 20 elements, 41 bytes. */
  private static final String TWENTY = "[" + String.join(",", Collections.nCopies(20, "1")) + "]";

  /** The result texts of {@code filter} on {@link #TWENTY} with {@code steps} steps; asserts that the run completes. */
  private static List<String> completes(String filter, long steps) {
    JqEngine engine = steps(steps);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, TWENTY.getBytes(UTF_8)).root();
    Results all = new Results();
    assertThat(engine.compile(filter).run(root, NONE, all, workspace)).as("run's answer for %s with %s steps", filter, steps).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  /** The result texts of {@code filter} on {@link #TWENTY} before it ends with STEPS under {@code steps} steps. */
  private static List<String> ends(String filter, long steps) {
    JqEngine engine = steps(steps);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, TWENTY.getBytes(UTF_8)).root();
    Results some = new Results();
    assertBudget(() -> engine.compile(filter).run(root, NONE, some, workspace), Budget.STEPS, filter + " with " + steps + " steps");
    return some.values.stream().map(JqValue::toJson).toList();
  }

  /** {@code filter} gives 1, 2 per element: 40 results with 41 steps, and ends after 39 with 40. */
  private static void assertOneTwoPerElement(String filter) {
    List<String> all = completes(filter, 41);
    assertThat(all).as("results of %s with 41 steps", filter).hasSize(40);
    assertThat(all.subList(0, 4)).as("the first results of %s", filter).containsExactly("1", "2", "1", "2");
    assertThat(ends(filter, 40)).as("results of %s with 40 steps before the end", filter).hasSize(39);
  }

  /** {@code filter} gives 1 per element: 20 results with 41 steps, and ends after all 20 with 40. */
  private static void assertOnePerElement(String filter) {
    List<String> all = completes(filter, 41);
    assertThat(all).as("results of %s with 41 steps", filter).hasSize(20);
    assertThat(all.stream().distinct().toList()).as("the result texts of %s", filter).containsExactly("1");
    assertThat(ends(filter, 40)).as("results of %s with 40 steps before the end", filter).hasSize(20);
  }

  @Test
  void theWitnessCommaTakesFortyOneSteps() {
    assertOneTwoPerElement(".[] | (1, 2)");
  }

  @Test
  void aTryTakesTheStepsOfItsBody() {
    // A tick on entering the Try would make 61 steps; the run with 41 would end.
    assertOneTwoPerElement(".[] | try (1, 2) catch 3");
  }

  @Test
  void aPostfixTryTakesTheStepsOfItsBody() {
    assertOneTwoPerElement(".[] | (1, 2)?");
  }

  @Test
  void enteringTheHandlerAndRaisingTakeNoStep() {
    // O4 (design approval): the handler entry does not tick, and error neither ticks nor charges. The handler's comma
    // ticks once per element as the witness's does.
    assertOneTwoPerElement(".[] | try error(\"x\") catch (1, 2)");
  }

  @Test
  void anElifChainTakesTheStepsOfItsBranch() {
    assertOneTwoPerElement(".[] | if false then 3 elif . then (1, 2) end");
  }

  @Test
  void aLabelTakesTheStepsOfItsBody() {
    assertOneTwoPerElement(".[] | label $l | (1, 2)");
  }

  @Test
  void theWitnessCommaWithEmptyTakesFortyOneSteps() {
    assertOnePerElement(".[] | 1, empty");
  }

  @Test
  void aBreakTakesTheStepsOfEmpty() {
    // The comma's right branch ticks as it does for empty; the Break and the Label add none.
    assertOnePerElement(".[] | label $l | 1, break $l");
  }

  @Test
  void raisingAndCatchingBuildsNothing() {
    // Witness: a budget of 0 bytes built is live; collecting one element charges it (ADR-0035).
    JqEngine none = bytesBuilt(0);
    Results cut = new Results();
    assertBudget(() -> none.compile("[.]").run(JqNull.NULL, NONE, cut, none.newWorkspace()), Budget.BYTES_BUILT, "[.] under 0 bytes built");

    Results all = new Results();
    assertThat(none.compile("try error(\"x\") catch .").run(JqNull.NULL, NONE, all, none.newWorkspace()))
        .as("run's answer for try error(\"x\") catch . under 0 bytes built")
        .isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of try error(\"x\") catch .").containsExactly("\"x\"");
  }
}
