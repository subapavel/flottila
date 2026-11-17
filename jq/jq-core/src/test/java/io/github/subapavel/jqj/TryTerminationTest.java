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
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * No catch set holds a termination (ADR-0008, ADR-0039; GLOSSARY "Catch set"): a budget overrun inside a try's body,
 * a postfix {@code ?}, a handler, nested trys or a label ends the run as {@code JqTermination(BUDGET, …)}, and no
 * handler result reaches the sink. Without the budget each filter completes on jq-ref 1.8.2 with 1, 2 per element and
 * no handler result (observed 2026-11-13 on the 20 ones, see ControlStepsTest).
 *
 * <p>The cut: on 20 ones, 1 read tick, then per element one tick before its emit and one for its comma (ADR-0034,
 * F-32). With 10 steps the 11th tick is the comma of the fifth element, after its left result: 9 results came first,
 * each 1 or 2.
 */
class TryTerminationTest {

  /** {@code [1,1,…,1]} with 20 elements. */
  private static final String TWENTY = "[" + String.join(",", Collections.nCopies(20, "1")) + "]";

  /** {@code filter} on {@link #TWENTY} ends with STEPS under 10 steps after the 9 results of the cut, none a handler's. */
  private static void assertEndsAfterNine(String filter) {
    JqEngine engine = steps(10);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, TWENTY.getBytes(UTF_8)).root();
    Results some = new Results();
    assertBudget(() -> engine.compile(filter).run(root, NONE, some, workspace), Budget.STEPS, filter + " with 10 steps");
    assertThat(some.values.stream().map(JqValue::toJson).toList()).as("results of %s before the end", filter).containsExactly("1", "2", "1", "2", "1", "2", "1", "2", "1");
  }

  @Test
  void aStepOverrunInsideATryBodyIsNoCaughtError() {
    assertEndsAfterNine("try (.[] | (1, 2)) catch \"caught\"");
  }

  @Test
  void aStepOverrunInsideAPostfixTryIsNoCaughtError() {
    assertEndsAfterNine("(.[] | (1, 2))?");
  }

  @Test
  void aStepOverrunPassesNestedTrys() {
    assertEndsAfterNine("try (try (.[] | (1, 2)) catch \"inner\") catch \"outer\"");
  }

  @Test
  void aStepOverrunInsideATryInsideALabelEndsTheRun() {
    assertEndsAfterNine("label $l | try (.[] | (1, 2)) catch \"caught\"");
  }

  @Test
  void aStepOverrunInsideAHandlerEndsTheRun() {
    // The handler runs on "x" and its four commas tick; with 2 steps the third comma tick ends the run after three
    // results. jq-ref: [try error("x") catch (.,.,.,.,.)] gives five "x" (observed 2026-11-13).
    JqEngine engine = steps(2);
    Results some = new Results();
    assertBudget(
        () -> engine.compile("try error(\"x\") catch (., ., ., ., .)").run(JqNull.NULL, NONE, some, engine.newWorkspace()),
        Budget.STEPS,
        "the handler's commas with 2 steps");
    assertThat(some.values).as("results before the end").hasSize(3).allSatisfy(v -> assertThat(((JqString) v).value()).isEqualTo("x"));
  }

  @Test
  void aBytesBuiltOverrunInsideATryIsNoCaughtError() {
    // Collecting one element charges it before storing it (ADR-0035), so under 0 bytes built [.] ends the run.
    JqEngine engine = bytesBuilt(0);
    Results none = new Results();
    assertBudget(
        () -> engine.compile("try [.] catch \"caught\"").run(JqNull.NULL, NONE, none, engine.newWorkspace()), Budget.BYTES_BUILT, "try [.] under 0 bytes built");
    assertThat(none.values).as("results of try [.] catch \"caught\" under 0 bytes built").isEmpty();
  }
}
