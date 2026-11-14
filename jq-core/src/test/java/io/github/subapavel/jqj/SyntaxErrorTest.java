package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code JqEngine.compile} on a filter jq's grammar rejects (ADR-0032, GLOSSARY "Syntax error", "Recovery message",
 * "Error block"). The parse collects its errors and throws them before resolution, so no Stub hides one. A syntax
 * block is jqj's own text up to the token (DEVIATIONS "Syntax-error text"): only {@code syntax error, unexpected <jq
 * token name>} must match jq's, then jq's position, source line and carets (ADR-0031). A recovery block and the
 * "not given" message match jq's text exactly. The command line's rc and stdout are checked by diff/syntax.cases;
 * this test checks what the differential oracle does not compare for a syntax error: the exception's blocks, line
 * and column. Every expected value is jq-ref 1.8.2's recording in diff/syntax.rec unless a comment names jq.test.
 */
class SyntaxErrorTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /** jq's text for a program with no top-level filter, which is no syntax error (ADR-0032). */
  private static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")";

  private static final JqEngine ENGINE = JqEngine.builder().build();

  /**
   * One syntax error as jq-ref recorded it.
   *
   * @param unexpected jq's token name after {@code unexpected }
   * @param tail jq's source line and caret line after the position, each indented by four spaces
   */
  private record SyntaxError(String text, String unexpected, int line, int column, String tail) {}

  @Test
  void aNonAssociativeOperatorIsASyntaxErrorAtItsSecondUse() {
    // diff/syntax.rec: compare-nonassoc, compare-nonassoc-around-a-sum (+ binds tighter than <, so the second < is
    // the error), compare-nonassoc-line-3, equal-nonassoc, assign-nonassoc, update-nonassoc.
    onlySyntaxErrors(
        new SyntaxError("1 < 2 < 3", "'<'", 1, 7, "    1 < 2 < 3\n          ^"),
        new SyntaxError("1 < 2 + 3 < 4", "'<'", 1, 11, "    1 < 2 + 3 < 4\n              ^"),
        new SyntaxError("1 +\n2 <\n3 < 4", "'<'", 3, 3, "    3 < 4\n      ^"),
        new SyntaxError("1 == 1 == true", "==", 1, 8, "    1 == 1 == true\n           ^^"),
        new SyntaxError(".a = .b = 1", "'='", 1, 9, "    .a = .b = 1\n            ^"),
        new SyntaxError(".a |= 1 |= 2", "|=", 1, 9, "    .a |= 1 |= 2\n            ^^"));
  }

  @Test
  void aPlaceThatTakesAPostfixTermRejectsAnExpression() {
    // diff/syntax.rec: object-value-as, def-after-an-operator, try-body-operator-in-array, try-body-operator,
    // reduce-source-comma. Then jq.test %%FAIL lines 548, 554, 1988, 2075 and 2081, whose unexpected parts and
    // positions are jq.test's (the census cases of S2a.12).
    onlySyntaxErrors(
        new SyntaxError("{a: 1 as $x | $x}", "as", 1, 7, "    {a: 1 as $x | $x}\n          ^^"),
        new SyntaxError("1 + def f: 2; f", "def", 1, 5, "    1 + def f: 2; f\n        ^^^"),
        new SyntaxError("[try error(\"x\") + 1 catch .]", "catch", 1, 21, "    [try error(\"x\") + 1 catch .]\n                        ^^^^^"),
        new SyntaxError("try error(\"x\") + 1 catch .", "catch", 1, 20, "    try error(\"x\") + 1 catch .\n                       ^^^^^"),
        new SyntaxError("reduce 1, 2 as $x (0; . + $x)", "','", 1, 9, "    reduce 1, 2 as $x (0; . + $x)\n            ^"),
        new SyntaxError(". as [] | null", "']'", 1, 7, "    . as [] | null\n          ^"),
        new SyntaxError(". as {} | null", "'}'", 1, 7, "    . as {} | null\n          ^"),
        new SyntaxError("%::wat", "'%'", 1, 1, "    %::wat\n    ^"),
        new SyntaxError("{", "end of file", 1, 1, "    {\n    ^"),
        new SyntaxError("}", "INVALID_CHARACTER", 1, 1, "    }\n    ^"));
  }

  @Test
  void anObjectKeyExpressionGivesTheSyntaxBlockThenTheRecoveryMessage() {
    // diff/syntax.rec object-key-expression (jq.test line 133 checks the same last block): two blocks, "jq: 2 compile
    // errors".
    JqCompileException one = error("{1+2:3}");
    assertThat(one.blocks()).as("blocks of {1+2:3}").hasSize(2);
    syntaxBlock(one.blocks().get(0), "{1+2:3}", "LITERAL", "line 1, column 2:\n    {1+2:3}\n     ^");
    assertThat(one.blocks().get(1))
        .as("recovery block of {1+2:3}")
        .isEqualTo("jq: error: May need parentheses around object key expression at <top-level>, line 1, column 2:\n    {1+2:3}\n     ^^^");
    assertThat(one.getMessage()).as("message of {1+2:3}").endsWith("\njq: 2 compile errors");
    assertThat(one.line()).as("line of {1+2:3}").isEqualTo(1);
    assertThat(one.column()).as("column of {1+2:3}").isEqualTo(2);

    // object-key-expression-twice: after a recovery the parse goes on, so the second object gives its two blocks.
    String text = "{1+2:3} | {4*5:6}";
    JqCompileException two = error(text);
    assertThat(two.blocks()).as("blocks of %s", text).hasSize(4);
    syntaxBlock(two.blocks().get(0), text, "LITERAL", "line 1, column 2:\n    {1+2:3} | {4*5:6}\n     ^");
    assertThat(two.blocks().get(1))
        .as("first recovery block of %s", text)
        .isEqualTo("jq: error: May need parentheses around object key expression at <top-level>, line 1, column 2:\n    {1+2:3} | {4*5:6}\n     ^^^");
    syntaxBlock(two.blocks().get(2), text, "LITERAL", "line 1, column 12:\n    {1+2:3} | {4*5:6}\n               ^");
    assertThat(two.blocks().get(3))
        .as("second recovery block of %s", text)
        .isEqualTo("jq: error: May need parentheses around object key expression at <top-level>, line 1, column 12:\n    {1+2:3} | {4*5:6}\n               ^^^");
    assertThat(two.getMessage()).as("message of %s", text).endsWith("\njq: 4 compile errors");
  }

  @Test
  void anUnusualNameAfterADotGivesTheSyntaxBlockThenTheRecoveryMessage() {
    // diff/syntax.rec dot-e-acute, dot-at, dot-backtick, dot-brace: two blocks each, "jq: 2 compile errors".
    for (String[] c : new String[][] {{".é", "INVALID_CHARACTER"}, {".@", "INVALID_CHARACTER"}, {".`", "INVALID_CHARACTER"}, {".{", "'{'"}}) {
      String text = c[0];
      JqCompileException e = error(text);
      assertThat(e.blocks()).as("blocks of %s", text).hasSize(2);
      syntaxBlock(e.blocks().get(0), text, c[1], "line 1, column 2:\n    " + text + "\n     ^");
      assertThat(e.blocks().get(1))
          .as("recovery block of %s", text)
          .isEqualTo("jq: error: try .[\"field\"] instead of .field for unusually named fields at <top-level>, line 1, column 1:\n    " + text + "\n    ^^");
      assertThat(e.getMessage()).as("message of %s", text).endsWith("\njq: 2 compile errors");
      assertThat(e.line()).as("line of %s", text).isEqualTo(1);
      assertThat(e.column()).as("column of %s", text).isEqualTo(2);
    }
    // Witness (field-dot-e-acute): after a field the same byte gives the syntax block alone, no recovery.
    onlySyntaxErrors(new SyntaxError(".a.é", "INVALID_CHARACTER", 1, 4, "    .a.é\n       ^"));
  }

  @Test
  void aProgramWithoutATopLevelFilterIsNotGiven() {
    // diff/syntax.rec program-empty, program-only-definitions, program-only-two-definitions, program-only-comment,
    // program-only-spaces, program-only-lf: rc 3 and exactly this text (no syntax error, so it matches jq's).
    for (String text : List.of("", "def f: 1;", "def f: 1; def g: f;", "#c", "   ", "\n")) {
      JqCompileException e = error(text);
      assertThat(e.getMessage()).as("message of %s", show(text)).isEqualTo(NOT_GIVEN + "\njq: 1 compile error");
      assertThat(e.blocks()).as("blocks of %s", show(text)).containsExactly(NOT_GIVEN);
    }
    // Witnesses: a definition followed by a filter is a program (jq-ref 1.8.2, observed 2026-11-12: 'def f: 1; .' on
    // null -> null, rc 0), which compiles since #70 lowers def; one followed by .., which still stubs, gets past the
    // not-given check to the Stub; and . alone compiles.
    assertThat(ENGINE.compile("def f: 1; .")).as("compile of def f: 1; .").isNotNull();
    assertThatExceptionOfType(JqCompileException.class)
        .as("compile of def f: 1; ..")
        .isThrownBy(() -> ENGINE.compile("def f: 1; .."))
        .withMessageStartingWith(MARK);
    assertThat(ENGINE.compile(".")).as("compile of .").isNotNull();
  }

  @Test
  void theEndOfTheFileIsReportedAtTheLexersLastMatch() {
    // basics/syntax-error-eof and diff/syntax.rec eof-*: after the last token, a whole whitespace run (its carets stop
    // at the line end); after a comment, its last byte, or the LF that ends it.
    onlySyntaxErrors(
        new SyntaxError(".a |", "end of file", 1, 4, "    .a |\n       ^"),
        new SyntaxError(".a |   ", "end of file", 1, 5, "    .a |   \n        ^^^"),
        new SyntaxError(".a |\n", "end of file", 1, 5, "    .a |\n        ^"),
        new SyntaxError(".a |\n\n  ", "end of file", 1, 5, "    .a |\n        ^"),
        new SyntaxError(".a |\r", "end of file", 1, 5, "    .a |\r\n        ^"),
        new SyntaxError(".a | # cd", "end of file", 1, 9, "    .a | # cd\n            ^"),
        new SyntaxError(".a | # c\n", "end of file", 1, 9, "    .a | # c\n            ^"),
        new SyntaxError(".a | # c\n ", "end of file", 2, 1, "     \n    ^"),
        new SyntaxError(".a | #", "end of file", 1, 6, "    .a | #\n         ^"),
        new SyntaxError(".a | # c\\\nd", "end of file", 2, 1, "    d\n    ^"),
        new SyntaxError(".a\n|\n", "end of file", 2, 2, "    |\n     ^"));
  }

  @Test
  void noStubHidesASyntaxError() {
    // ADR-0032: each text holds .., a Stub until spec 2b, before its syntax error, and the syntax error comes first.
    // jq-ref 1.8.2, observed 2026-11-14: each -> rc 3, this one block, then "jq: 1 compile error".
    onlySyntaxErrors(
        new SyntaxError(".. +", "end of file", 1, 4, "    .. +\n       ^"),
        new SyntaxError(".a, .. |", "end of file", 1, 8, "    .a, .. |\n           ^"),
        new SyntaxError(".. | )", "INVALID_CHARACTER", 1, 6, "    .. | )\n         ^"));
    // Witness: without the error .. is a Stub (jq-ref -c '..' on [1] -> [1] 1, CompileTest).
    assertThatExceptionOfType(JqCompileException.class).as("compile of ..").isThrownBy(() -> ENGINE.compile("..")).withMessageStartingWith(MARK);
  }

  @Test
  void stringPartsAreParsedSoAnErrorInOrAfterAnInterpolationIsReported() {
    // diff/syntax.rec after-an-interpolation, after-a-format-string, in-interpolation, in-interpolation-wrong-closer,
    // in-interpolation-nonassoc.
    onlySyntaxErrors(
        new SyntaxError("\"a\\(1)b\" |", "end of file", 1, 10, "    \"a\\(1)b\" |\n             ^"),
        new SyntaxError("@base64 \"x\\(.)\" +", "end of file", 1, 17, "    @base64 \"x\\(.)\" +\n                    ^"),
        new SyntaxError("\"\\(1 +)\"", "QQSTRING_INTERP_END", 1, 7, "    \"\\(1 +)\"\n          ^"),
        new SyntaxError("\"\\(1]\"", "INVALID_CHARACTER", 1, 5, "    \"\\(1]\"\n        ^"),
        new SyntaxError("\"a\\(1 < 2 < 3)\"", "'<'", 1, 11, "    \"a\\(1 < 2 < 3)\"\n              ^"));
    // Witness: lowering an interpolation stayed a Stub until #67 (F-35); jq-ref -c '"a\(1)b"' on null -> "a1b". #67
    // lowers it, so the witness ends with | .., which still stubs: without the syntax error the text reaches the
    // resolver.
    assertThatExceptionOfType(JqCompileException.class)
        .as("compile of \"a\\(1)b\" | ..")
        .isThrownBy(() -> ENGINE.compile("\"a\\(1)b\" | .."))
        .withMessageStartingWith(MARK);
  }

  /** Each text gives exactly its one syntax block, then "jq: 1 compile error", with its line and column. */
  private static void onlySyntaxErrors(SyntaxError... errors) {
    for (SyntaxError s : errors) {
      JqCompileException e = error(s.text());
      assertThat(e.blocks()).as("blocks of %s", show(s.text())).hasSize(1);
      syntaxBlock(e.blocks().getFirst(), show(s.text()), s.unexpected(), "line " + s.line() + ", column " + s.column() + ":\n" + s.tail());
      assertThat(e.getMessage()).as("message of %s", show(s.text())).endsWith("\njq: 1 compile error");
      assertThat(e.line()).as("line of %s", show(s.text())).isEqualTo(s.line());
      assertThat(e.column()).as("column of %s", show(s.text())).isEqualTo(s.column());
    }
  }

  /**
   * A syntax block: jq's {@code syntax error, unexpected <token>} part, any text of jqj's own, then jq's position,
   * source line and carets ({@code position} starts at {@code line }).
   */
  private static void syntaxBlock(String block, String what, String unexpected, String position) {
    assertThat(block)
        .as("syntax block of %s", what)
        .startsWith("jq: error: syntax error, unexpected " + unexpected)
        .endsWith(" at <top-level>, " + position);
  }

  private static JqCompileException error(String text) {
    return assertThatExceptionOfType(JqCompileException.class).as("compile of %s", show(text)).isThrownBy(() -> ENGINE.compile(text)).actual();
  }

  /** The text with its control characters spelled out, for a readable description. */
  private static String show(String text) {
    return "'" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
  }
}
