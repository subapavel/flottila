package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ADR-0012 and ADR-0037: a read literal that is canonical and at most 15 bytes long is a short literal, and two short
 * literals compare by their doubles, which ticks nothing; a longer one compares on the BigDecimal path, one tick per
 * started KiB of each literal. {@code .[0] < .[1]} on a read array of two decimals that fit no long: 1 read tick (a
 * document under 1 KiB), 1 walk tick for {@code .[1]}, and the compare's ticks, so 2 steps for two 15-byte literals and 4
 * for two 16-byte ones; with one step fewer the run ends with {@code JqTermination(BUDGET, STEPS)}. jq-ref 1.8.2 gives
 * true for both ({@code jq-ref -nc}, 2026-11-14) and prints each literal as written, so each is canonical.
 */
class ShortLiteralCompareStepsTest {
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          [1.2345678901234,1.2345678901235]   => 2
          [1.23456789012345,1.23456789012346] => 4
          """)
  void aCompareOfTwoReadLiteralsTicksOnlyOnTheBigDecimalPath(String document, int least) {
    JqEngine exact = steps(least);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, document).root();
    Results results = new Results();
    assertThat(exact.compile(".[0] < .[1]").run(root, NONE, results, whole)).as("run's answer for .[0] < .[1] on %s with %d steps", document, least).isTrue();
    assertThat(results.values).as("results of .[0] < .[1] on %s", document).containsExactly(JqBool.TRUE);
    JqEngine under = steps(least - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, document).root();
    assertBudget(() -> under.compile(".[0] < .[1]").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, ".[0] < .[1] on " + document + " with " + (least - 1) + " steps");
  }
}
