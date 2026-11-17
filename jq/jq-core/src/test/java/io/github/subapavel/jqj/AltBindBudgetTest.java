package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Collections;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * The {@code ?//} tick site at the public API (#67's acceptance, ADR-0008's F-32 amendment, ADR-0038): entering each
 * alternative after the first charges one step, the first alternative none, and nothing else of a pattern charges a
 * step on a number. A run may take exactly its budget of ticks and the next one ends it with {@code JqTermination(BUDGET,
 * STEPS)} (ADR-0030), so each count has an adjacent pair: N steps complete, N - 1 end. The counts come from those
 * rules: a document under 1 KiB reads in one tick (ADR-0015, StepBudgetTest), {@code .[]} charges one step per element
 * before its emit and {@code .[0]} the zero entries it steps over (ADR-0034, NavigationBudgetTest). The results are
 * jq-ref 1.8.2's: {@code .[] as [$a] ?// $a | $a} gives each number of an array of numbers, and each first element of an
 * array of one-element arrays (diff/bind.rec, bind/alt-second-on-match-error and bind/alt-per-result-restart).
 */
class AltBindBudgetTest {

  /** {@code [1,1,…,1]} with {@code n} elements, {@code 2n + 1} bytes. */
  private static String numbers(int n) {
    return "[" + String.join(",", Collections.nCopies(n, "1")) + "]";
  }

  /** {@code [[1],[1],…,[1]]} with {@code n} elements. */
  private static String singletons(int n) {
    StringJoiner array = new StringJoiner(",", "[", "]");
    for (int i = 0; i < n; i++) array.add("[1]");
    return array.toString();
  }

  /** {@code filter} on {@code json} completes with {@code steps} steps, giving {@code count} ones. */
  private static void assertCompletes(String filter, String json, long steps, int count) {
    JqEngine engine = steps(steps);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    Results all = new Results();
    assertThat(engine.compile(filter).run(root, NONE, all, workspace)).as("run's answer for %s with %s steps", filter, steps).isTrue();
    assertThat(all.values).as("results of %s with %s steps", filter, steps).hasSize(count);
    assertThat(all.values.stream().map(JqValue::toJson).distinct().toList()).as("the texts of %s", filter).containsExactly("1");
  }

  /** {@code filter} on {@code json} ends with {@code JqTermination(BUDGET, STEPS)} under {@code steps} steps, after {@code count} results. */
  private static void assertEnds(String filter, String json, long steps, int count) {
    JqEngine engine = steps(steps);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    Results some = new Results();
    assertBudget(() -> engine.compile(filter).run(root, NONE, some, workspace), Budget.STEPS, filter + " with " + steps + " steps");
    assertThat(some.values).as("results of %s with %s steps before the end", filter, steps).hasSize(count);
  }

  @Test
  void aChainOverALongArrayEndsAsStepBudget() {
    // #67's acceptance: 300 numbers, 601 bytes, one read tick. Each element ticks before its emit and once more on
    // entering the second alternative, so with 10 steps the fifth element's second alternative is the 11th tick: four
    // results came before it.
    assertEnds(".[] as [$a] ?// $a | $a", numbers(300), 10, 4);
  }

  @Test
  void eachElementThatEntersTheSecondAlternativeChargesOneStep() {
    // 20 numbers: 1 read tick, 20 element ticks and 20 second-alternative ticks, 41 in all.
    assertCompletes(".[] as [$a] ?// $a | $a", numbers(20), 41, 20);
    // With 40 the last element's second alternative is the 41st tick, before its emit.
    assertEnds(".[] as [$a] ?// $a | $a", numbers(20), 40, 19);
  }

  @Test
  void eachLaterAlternativeEnteredChargesOneStep() {
    // Three alternatives over 20 numbers: [$a] and {a:$a} both fail on a number, so each element enters the second and
    // the third: 1 + 20 + 40 = 61 ticks. A charge per chain instead of per alternative would complete with 41.
    assertCompletes(".[] as [$a] ?// {a: $a} ?// $a | $a", numbers(20), 61, 20);
    assertEnds(".[] as [$a] ?// {a: $a} ?// $a | $a", numbers(20), 60, 19);
  }

  @Test
  void theFirstAlternativeChargesNothing() {
    // 20 one-element arrays: [$a] matches each, so no later alternative is entered: 1 read tick, 20 element ticks and
    // .[0] stepping over no entry, 21 in all.
    assertCompletes(".[] as [$a] ?// $a | $a", singletons(20), 21, 20);
    assertEnds(".[] as [$a] ?// $a | $a", singletons(20), 20, 19);
  }
}
