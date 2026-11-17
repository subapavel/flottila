package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The loop state an update with no result leaves is jq's null (ADR-0041, GLOSSARY "Loop state"): what the reduce emits
 * and what the next update starts from, and so is the state a failed ?// attempt took, which nothing rolls back. Each
 * expected text is jq-ref 1.8.2's with {@code -nc}, observed 2026-11-13 (recorded too in diff/reduce.rec under the
 * names given).
 */
class LoopStateTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  private static List<String> results(String filter) {
    Results all = new Results();
    assertThat(ENGINE.compile(filter).run(JqNull.NULL, NONE, all, ENGINE.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  @Test
  void aReduceWhoseLastUpdateGaveNoResultEmitsNull() {
    // reduce/update-empty.
    assertThat(results("reduce (1,2) as $x (5; empty)")).as("reduce (1,2) as $x (5; empty)").containsExactly("null");
    // Witness: the same reduce with a result emits it.
    assertThat(results("reduce (1,2) as $x (5; $x)")).as("reduce (1,2) as $x (5; $x)").containsExactly("2");
  }

  @Test
  void theUpdateAfterAnEmptyOneStartsFromNull() {
    // reduce/update-empty-then-next and foreach-update-empty-then-next.
    assertThat(results("reduce ([], [3]) as $x (7; $x[] as $y | [., $y])")).as("reduce ([], [3]) ...").containsExactly("[null,3]");
    assertThat(results("[foreach ([], [3]) as $x (7; $x[] as $y | [., $y])]")).as("[foreach ([], [3]) ...]").containsExactly("[[null,3]]");
  }

  @Test
  void aRetriedAlternativeStartsFromTheStateTheFailedOneTook() {
    // reduce/altbind-no-rollback: the first alternative's update on [2] raised after taking the state 1, so the
    // second runs on null, and null + [2] is [2].
    assertThat(results("reduce ([1],[2]) as [$a] ?// $a (0; if $a == 2 then error(\"x\") else .+$a end)")).as("reduce ... ?// ...").containsExactly("[2]");
  }
}
