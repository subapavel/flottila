package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * A comparison of two constants folds as jq's constant_fold does, any kind and nested (ADR-0037, ADR-0036, GLOSSARY
 * "Fold", "Constant key"), so as an object key it is a constant boolean and jq's parse-time error; {@code and},
 * {@code or}, {@code //} and {@code not} are not folded, and their non-string key fails at run time. The differential
 * oracle compares rc and stdout alone for these recordings; this test checks the blocks and the run-time texts. Every
 * expected value is jq-ref 1.8.2's recording in diff/compare.rec, case named at each check. A fold that raises cannot
 * be reached here: a comparison raises only past 10 000 levels, and jqj's parser stops at 512 (DEVIATIONS "Parse
 * depth").
 */
class ComparisonFoldTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void aComparisonOfTwoConstantsIsAConstantKey() {
    // compare/key-folded-less, key-folded-not-equal, key-folded-containers: the carets span the key expression.
    assertBlock("{(1<2):1}", "boolean (true)", "^^^");
    assertBlock("{(1 != 1):1}", "boolean (false)", "^^^^^^");
    assertBlock("{([1] < [2]):1}", "boolean (true)", "^^^^^^^^^");
    assertBlock("{({} == {}):1}", "boolean (true)", "^^^^^^^^");
  }

  @Test
  void aComparisonOfAFoldedComparisonFoldsToo() {
    // compare/key-folded-nested: (1 < 2) folds to true, then true == true.
    assertBlock("{((1 < 2) == true):1}", "boolean (true)", "^^^^^^^^^^^^^^^");
  }

  @Test
  void andOrAlternativeAndNotAreNotFolded() {
    // compare/key-not-folded-and, -or, -alternative, -comparison-and, -not: each compiles and fails on null (rc 5).
    assertRunTimeKey("{(true and true):1}", "Cannot use boolean (true) as object key");
    assertRunTimeKey("{(false or true):1}", "Cannot use boolean (true) as object key");
    assertRunTimeKey("{(null // 1):1}", "Cannot use number (1) as object key");
    assertRunTimeKey("{(1 < 2 and true):1}", "Cannot use boolean (true) as object key");
    assertRunTimeKey("{(\"a\" < \"b\" | not):1}", "Cannot use boolean (false) as object key");
  }

  /** {@code text} fails to compile with the one block of jq's key error under the source line. */
  private static void assertBlock(String text, String key, String carets) {
    JqCompileException e = assertThatExceptionOfType(JqCompileException.class).as("compile of %s", text).isThrownBy(() -> ENGINE.compile(text)).actual();
    assertThat(e.blocks())
        .as("blocks of %s", text)
        .containsExactly("jq: error: Cannot use " + key + " as object key at <top-level>, line 1, column 3:\n    " + text + "\n      " + carets);
    assertThat(e.getMessage()).as("message of %s", text).endsWith("\njq: 1 compile error");
  }

  /** {@code text} compiles, and its run on null fails with {@code message} before any result. */
  private static void assertRunTimeKey(String text, String message) {
    JqFilter filter = ENGINE.compile(text);
    Results results = new Results();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as("run of %s on null", text)
            .isThrownBy(() -> filter.run(JqNull.NULL, RunBudgets.NONE, results, ENGINE.newWorkspace()))
            .actual();
    JqValue value = e.value();
    assertThat(value).as("error value of %s", text).isInstanceOf(JqString.class);
    assertThat(((JqString) value).value()).as("error text of %s", text).isEqualTo(message);
    assertThat(results.values).as("results of %s", text).isEmpty();
  }
}
