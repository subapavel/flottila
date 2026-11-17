package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Containers built in a loop are charged where they are built (ADR-0035, ADR-0041): the loop itself charges nothing,
 * so {@code reduce range(0;n) as $i (null; [.])} charges n collections of one element, n x (CONTAINER 64 + ELEMENT 8) =
 * 72 n bytes (bench/RESULTS.md, 2026-11-13, as BytesBuiltBudgetTest pins {@code [.[]]} on [1,2,3] at 88). Each charge
 * has an adjacent pair: the run completes under it and ends with BYTES_BUILT one byte under it. jq-ref 1.8.2, observed
 * 2026-11-13: n = 0 gives null, n = 1 [null], n = 10 ten arrays around null.
 */
class ReduceBuiltTest {

  private static String filter(int n) {
    return "reduce range(0;" + n + ") as $i (null; [.])";
  }

  private static List<String> completes(int n, long bytes) {
    JqEngine engine = bytesBuilt(bytes);
    Results all = new Results();
    assertThat(engine.compile(filter(n)).run(JqNull.NULL, NONE, all, engine.newWorkspace())).as("run's answer for n = %s under %s bytes", n, bytes).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  private static void ends(int n, long bytes) {
    JqEngine engine = bytesBuilt(bytes);
    Results none = new Results();
    assertBudget(() -> engine.compile(filter(n)).run(JqNull.NULL, NONE, none, engine.newWorkspace()), Budget.BYTES_BUILT, filter(n) + " under " + bytes + " bytes");
    assertThat(none.values).as("results for n = %s under %s bytes", n, bytes).isEmpty();
  }

  @Test
  void noIterationBuildsNothing() {
    assertThat(completes(0, 0)).as("results for n = 0 under 0 bytes").containsExactly("null");
  }

  @Test
  void oneIterationChargesOneCollection() {
    assertThat(completes(1, 72)).as("results for n = 1 under 72 bytes").containsExactly("[null]");
    ends(1, 71);
  }

  @Test
  void tenIterationsChargeTenCollections() {
    assertThat(completes(10, 720)).as("results for n = 10 under 720 bytes").containsExactly("[[[[[[[[[[null]]]]]]]]]]");
    ends(10, 719);
  }
}
