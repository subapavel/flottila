package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * A constant key that is not a string is a parser-action error (ADR-0036, GLOSSARY "Constant key"): it is recorded
 * when jq's action runs, with jq's message and position, and the parse goes on, so its block and the syntax blocks
 * come in the order the parse met them (ADR-0031 for the block text and the count). A key known only at run time
 * fails at run time instead. The differential oracle compares rc and stdout alone for a recording with a syntax error
 * (DEVIATIONS "Syntax-error text"); this test checks the blocks. Every expected value is jq-ref 1.8.2's recording in
 * diff/construct.rec or diff/syntax.rec, case named at each check.
 */
class ConstantKeyTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void aConstantNumberKeyIsOneParseTimeError() {
    // construct/key-number-computed: rc 3, this one block, "jq: 1 compile error".
    JqCompileException e = error("{(1):2}");
    assertThat(e.blocks())
        .as("blocks of {(1):2}")
        .containsExactly("jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 3:\n    {(1):2}\n      ^");
    assertThat(e.getMessage()).as("message of {(1):2}").endsWith("\njq: 1 compile error");
    assertThat(e.line()).as("line of {(1):2}").isEqualTo(1);
    assertThat(e.column()).as("column of {(1):2}").isEqualTo(3);
  }

  @Test
  void aLiteralNumberKeyIsTheSyntaxErrorAndItsRecoveryNotAKeyError() {
    // syntax/object-key-number-literal: jq's grammar has no number key, so {1:2} is the syntax error and jq's recovery
    // message, two blocks, like {1+2:3}; no "Cannot use number" block.
    JqCompileException e = error("{1:2}");
    assertThat(e.blocks()).as("blocks of {1:2}").hasSize(2);
    syntaxBlock(e.blocks().get(0), "{1:2}", "LITERAL", "line 1, column 2:\n    {1:2}\n     ^");
    assertThat(e.blocks().get(1))
        .as("recovery block of {1:2}")
        .isEqualTo("jq: error: May need parentheses around object key expression at <top-level>, line 1, column 2:\n    {1:2}\n     ^");
    assertThat(e.getMessage()).as("message of {1:2}").endsWith("\njq: 2 compile errors");
  }

  @Test
  void aKeyErrorComesBeforeALaterSyntaxError() {
    // syntax/object-key-error-then-syntax-error: the pair (1): 2 is reduced before the 3 is read.
    String text = "{(1): 2 3}";
    JqCompileException e = error(text);
    assertThat(e.blocks()).as("blocks of %s", text).hasSize(2);
    assertThat(e.blocks().get(0))
        .as("key block of %s", text)
        .isEqualTo("jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 3:\n    {(1): 2 3}\n      ^");
    syntaxBlock(e.blocks().get(1), text, "LITERAL", "line 1, column 9:\n    {(1): 2 3}\n            ^");
    assertThat(e.getMessage()).as("message of %s", text).endsWith("\njq: 2 compile errors");
    assertThat(e.line()).as("line of %s", text).isEqualTo(1);
    assertThat(e.column()).as("column of %s: the first block's", text).isEqualTo(3);
  }

  @Test
  void aKeyErrorComesAfterAnEarlierRecovery() {
    // syntax/object-key-error-after-a-recovery: the syntax block and its recovery, then the key of the second object.
    String text = "{1+2:3} | {(1):2}";
    JqCompileException e = error(text);
    assertThat(e.blocks()).as("blocks of %s", text).hasSize(3);
    syntaxBlock(e.blocks().get(0), text, "LITERAL", "line 1, column 2:\n    {1+2:3} | {(1):2}\n     ^");
    assertThat(e.blocks().get(1))
        .as("recovery block of %s", text)
        .isEqualTo("jq: error: May need parentheses around object key expression at <top-level>, line 1, column 2:\n    {1+2:3} | {(1):2}\n     ^^^");
    assertThat(e.blocks().get(2))
        .as("key block of %s", text)
        .isEqualTo("jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 13:\n    {1+2:3} | {(1):2}\n                ^");
    assertThat(e.getMessage()).as("message of %s", text).endsWith("\njq: 3 compile errors");
  }

  @Test
  void everyConstantKeyIsReportedAndARunTimeKeyIsNot() {
    // construct/key-two-errors: rc 3 and exactly this stderr; (.a) is no constant, so it gives no block.
    String text = "{(1):2} | {(true):3, (.a):4}";
    JqCompileException e = error(text);
    assertThat(e.getMessage())
        .as("message of %s", text)
        .isEqualTo(
            "jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 3:\n    " + text + "\n      ^\n"
                + "jq: error: Cannot use boolean (true) as object key at <top-level>, line 1, column 13:\n    " + text + "\n                ^^^^\n"
                + "jq: 2 compile errors");
  }

  @Test
  void aKeyJqFoldsToAConstantIsCheckedAtParseTime() {
    // construct/key-number-then-dot, key-dot-then-number, key-in-parentheses: jq's block spans the key expression.
    assertThat(error("{(1|.):1}").blocks())
        .as("blocks of {(1|.):1}")
        .containsExactly("jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 3:\n    {(1|.):1}\n      ^^^");
    assertThat(error("{(.|1):1}").blocks())
        .as("blocks of {(.|1):1}")
        .containsExactly("jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 3:\n    {(.|1):1}\n      ^^^");
    assertThat(error("{((1)):1}").blocks())
        .as("blocks of {((1)):1}")
        .containsExactly("jq: error: Cannot use number (1) as object key at <top-level>, line 1, column 3:\n    {((1)):1}\n      ^^^");
  }

  @Test
  void aKeyKnownOnlyAtRunTimeFailsAtRunTime() {
    // construct/key-number-at-run-time: {(.):1} on 1 compiles, then fails with this text (rc 5).
    JqFilter filter = ENGINE.compile("{(.):1}");
    Workspace workspace = ENGINE.newWorkspace();
    JqValue one = RunBudgets.read(workspace, "1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).root();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class).as("run of {(.):1} on 1").isThrownBy(() -> filter.run(one, RunBudgets.NONE, new Results(), workspace)).actual();
    assertThat(e.value()).as("error value of {(.):1} on 1").isInstanceOf(JqString.class);
    assertThat(((JqString) e.value()).value()).as("error text of {(.):1} on 1").isEqualTo("Cannot use number (1) as object key");

    // Witness, construct/key-string-computed: a constant string key is no error; {("a"):1} on null gives {"a":1}.
    Results results = new Results();
    assertThat(ENGINE.compile("{(\"a\"):1}").run(JqNull.NULL, RunBudgets.NONE, results, ENGINE.newWorkspace())).as("run of {(\"a\"):1}").isTrue();
    assertThat(results.values.stream().map(JqValue::toJson).toList()).as("results of {(\"a\"):1}").containsExactly("{\"a\":1}");
  }

  /** A syntax block: jq's {@code syntax error, unexpected <token>}, any text of jqj's own, then jq's position. */
  private static void syntaxBlock(String block, String what, String unexpected, String position) {
    assertThat(block)
        .as("syntax block of %s", what)
        .startsWith("jq: error: syntax error, unexpected " + unexpected)
        .endsWith(" at <top-level>, " + position);
  }

  private static JqCompileException error(String text) {
    return assertThatExceptionOfType(JqCompileException.class).as("compile of %s", text).isThrownBy(() -> ENGINE.compile(text)).actual();
  }
}
