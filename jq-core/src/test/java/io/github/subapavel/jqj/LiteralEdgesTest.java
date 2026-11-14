package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.internal.op.TextFormat;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The #61 literals at {@code JqEngine.compile} where the test-author's red does not reach (implementer's witnesses):
 * the surrogate-pair and short {@code \\u} escape errors, an escape run whose last byte takes the closing quote into its
 * U+FFFD, {@code true(} and format strings as Stubs, a number literal that no long holds, and a long key cut at a
 * character boundary. Every jq text was observed on jq-ref 1.8.2 on 2026-11-12, with the command quoted beside it; a
 * filter with an escape was given in a file ({@code jq-ref -n -f}), so no shell rewrote it.
 */
class LiteralEdgesTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** The one error block of compiling {@code text}, checked against jq's count line. */
  private static String onlyBlock(String text) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("compile of %s", text).isThrownBy(() -> ENGINE.compile(text)).actual();
    assertThat(e.blocks()).as("blocks of %s", text).hasSize(1);
    assertThat(e.getMessage()).as("message of %s", text).isEqualTo(e.blocks().getFirst() + "\njq: 1 compile error");
    return e.blocks().getFirst();
  }

  @Test
  void aHighSurrogateNeedsALowOneRightAfterIt() {
    // jq-ref -n -f on each text: rc 3, this block, "jq: 1 compile error". The second unit is no \\u, below the low
    // surrogates, or above them; the run is checked whole, so the column is its closing quote's.
    for (String run : new String[] {"\\ud800\\n\\n\\n", "\\ud800\\u0041", "\\ud800\\ue000"}) {
      String text = "\"" + run + "\"";
      assertThat(onlyBlock(text))
          .as("block of %s", text)
          .isEqualTo(
              "jq: error: Invalid \\uXXXX\\uXXXX surrogate pair escape at line 1, column 14 (while parsing '" + text
                  + "') at <top-level>, line 1, column 2:\n    " + text + "\n     ^^^^^^^^^^^^");
    }
    // Witness: jq-ref -n -f on "\\ud800\\ude00" -> "𐈀" (U+10200), rc 0.
    Results pair = new Results();
    ENGINE.compile("\"\\ud800\\ude00\"").run(JqNull.NULL, NONE, pair, ENGINE.newWorkspace());
    assertThat(pair.values).as("results of the surrogate pair").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) pair.values.getFirst()).value()).as("its text").isEqualTo(new String(Character.toChars(0x10200)));
  }

  @Test
  void theSurrogateRangesAtTheirBounds() {
    // jq-ref -n -c -f on '"\\ud7ff", "\\udc00", "\\ud800\\udc00", "\\udbff\\udfff" | explode' -> [55295], [65533],
    // [65536], [1114111]: below the high surrogates a unit stands alone, a lone low one becomes U+FFFD, and a pair at
    // either end of the ranges is one code point.
    String[][] cases = {{"\\ud7ff", "55295"}, {"\\udc00", "65533"}, {"\\ud800\\udc00", "65536"}, {"\\udbff\\udfff", "1114111"}};
    for (String[] c : cases) {
      Results results = new Results();
      ENGINE.compile("\"" + c[0] + "\"").run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
      assertThat(results.values).as("results of \"%s\"", c[0]).singleElement().isInstanceOf(JqString.class);
      assertThat(((JqString) results.values.getFirst()).value().codePoints().toArray())
          .as("code points of \"%s\"", c[0])
          .containsExactly(Integer.parseInt(c[1]));
    }
    // jq-ref -n -f on "\\udbff" and on "\\uZZZZ": rc 3, these blocks.
    assertThat(onlyBlock("\"\\udbff\""))
        .as("block of the last high surrogate alone")
        .isEqualTo(
            "jq: error: Invalid \\uXXXX\\uXXXX surrogate pair escape at line 1, column 8 (while parsing '\"\\udbff\"') at"
                + " <top-level>, line 1, column 2:\n    \"\\udbff\"\n     ^^^^^^");
    assertThat(onlyBlock("\"\\uZZZZ\""))
        .as("block of four letters that are no hex digits")
        .isEqualTo(
            "jq: error: Invalid characters in \\uXXXX escape at line 1, column 8 (while parsing '\"\\uZZZZ\"') at"
                + " <top-level>, line 1, column 2:\n    \"\\uZZZZ\"\n     ^^^^^^");
  }

  @Test
  void everyOneLetterEscapeAndALineFeedInARun() {
    // jq-ref -n -c -f on '"\"\\\/\b\f\n\r\t" | explode' -> [34,92,47,8,12,10,13,9].
    Results results = new Results();
    ENGINE.compile("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"").run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
    assertThat(((JqString) results.values.getFirst()).value().codePoints().toArray())
        .as("code points of the eight one-letter escapes")
        .containsExactly(34, 92, 47, 8, 12, 10, 13, 9);
    // jq-ref -n -f on a backslash and an LF in quotes: rc 3, this block; the column counts from the LF in the run.
    assertThat(onlyBlock("\"\\\n\""))
        .as("block of a backslash before an LF")
        .isEqualTo(
            "jq: error: Invalid escape at line 2, column 1 (while parsing '\"\\\n\"') at <top-level>, line 1, column 2:\n"
                + "    \"\\\n"
                + "     ^");
  }

  @Test
  void aShortUnicodeEscapeEndsAtTheFirstByteThatIsNoLetterOrDigit() {
    // jq-ref -n '"\\u_"': rc 3; the run is \\u alone, the underscore plain text.
    assertThat(onlyBlock("\"\\u_\""))
        .as("block of \"\\u_\"")
        .isEqualTo(
            "jq: error: Invalid \\uXXXX escape at line 1, column 4 (while parsing '\"\\u\"') at <top-level>, line 1, column 2:\n"
                + "    \"\\u_\"\n"
                + "     ^^");
  }

  @Test
  void aCutCharacterAtTheRunsEndTakesTheClosingQuote() {
    // jq-ref -n -f on "\\€" (the run is the backslash and the first byte of the three of €): rc 3, this block. jq's
    // wrapped string loses its closing quote to the U+FFFD of the cut sequence, so jv_parse meets the end in the
    // string.
    assertThat(onlyBlock("\"\\€\""))
        .as("block of \"\\€\"")
        .isEqualTo(
            "jq: error: Unfinished string at EOF at line 1, column 5 (while parsing '\"\\" + "\uFFFD" + "') at <top-level>, line 1,"
                + " column 2:\n    \"\\€\"\n     ^^");
    // Witness: jq-ref -n '"\\é"', a two-byte character, keeps the quote: "Invalid escape at line 1, column 6".
    assertThat(onlyBlock("\"\\é\"")).as("block of \"\\é\"").startsWith("jq: error: Invalid escape at line 1, column 6 (while parsing");
  }

  @Test
  void callsOfConstantsAreNotDefinedAndFormatStringsAreStubs() {
    // jq-ref 1.8.2, observed 2026-11-13 with -n: 'true(1)' and 'foo' give rc 3 and exactly these blocks. Since #70 a
    // call that binds nowhere is jq's compile error (ADR-0040): a constant called with arguments, and another name.
    Map<String, String> notDefined =
        Map.of(
            "true(1)", "jq: error: true/1 is not defined at <top-level>, line 1, column 1:\n    true(1)\n    ^^^^",
            "foo", "jq: error: foo/0 is not defined at <top-level>, line 1, column 1:\n    foo\n    ^^^");
    for (Map.Entry<String, String> e : notDefined.entrySet()) {
      JqCompileException error =
          assertThatExceptionOfType(JqCompileException.class).as("compile of %s", e.getKey()).isThrownBy(() -> ENGINE.compile(e.getKey())).actual();
      assertThat(error.blocks()).as("blocks of %s", e.getKey()).containsExactly(e.getValue());
      assertThat(error.getMessage()).as("message of %s", e.getKey()).isEqualTo(e.getValue() + "\njq: 1 compile error");
    }
    // jq-ref -n '@text "a"' -> "a" and '@base64' -> "bnVsbA==", rc 0. A format string and another format are not built
    // in #61. #67 lowers a format string, so its entry ends with | .., which still stubs.
    for (String text : new String[] {"@text \"a\" | ..", "@base64"}) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("compile of %s", text)
          .isThrownBy(() -> ENGINE.compile(text))
          .withMessageStartingWith(MARK);
    }
    // Witnesses: jq-ref -n 'true, @text' -> true and "null".
    Results results = new Results();
    ENGINE.compile("true").run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
    ENGINE.compile("@text").run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
    assertThat(results.values).as("results of true and @text").hasSize(2);
    assertThat(results.values.getFirst()).as("result of true").isSameAs(JqBool.TRUE);
    assertThat(((JqString) results.values.get(1)).value()).as("result of @text").isEqualTo("null");
  }

  @Test
  void textKeepsAStringAndWritesAnyOtherValue() {
    // jq-ref -n -c '("a" | @text), (1.0 | @text)' -> "a" and "1.0": a string is its own text, a number its JSON
    // text, the literal's canonical form.
    Results results = new Results();
    ENGINE.compile("\"a\" | @text").run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
    ENGINE.compile("1.0 | @text").run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
    assertThat(results.values).as("results").hasSize(2).allMatch(v -> v instanceof JqString);
    assertThat(((JqString) results.values.getFirst()).value()).as("text of \"a\"").isEqualTo("a");
    assertThat(((JqString) results.values.get(1)).value()).as("text of 1.0").isEqualTo("1.0");
    // Called directly: the same text, its ticks charged to an idle run state.
    assertThat(((JqString) new TextFormat().apply(new RunState(), JqNull.NULL)).value()).as("text of null under an idle run state").isEqualTo("null");
  }

  @Test
  void aNumberLiteralThatNoLongHoldsHasNoLongValue() {
    // JqNumber.longValue throws ArithmeticException unless fitsLong (ADR-0012); jq-ref -n '1.5 | type' -> "number".
    for (String text : new String[] {"1.5", "100000000000000000001"}) {
      JqNumber n = number(text);
      assertThat(n.fitsLong()).as("fitsLong of %s", text).isFalse();
      assertThatExceptionOfType(ArithmeticException.class).as("longValue of %s", text).isThrownBy(n::longValue);
    }
    // Witness: 7 holds.
    assertThat(number("7").longValue()).as("longValue of 7").isEqualTo(7L);
  }

  @Test
  void aLongKeyIsCutAtACharacterBoundary() {
    // jq-ref -n '"x" | ."<key>"', rc 5: the key's dump keeps 25 bytes, stepped back to the lead byte of the character
    // they cut, then "...". Three € (three bytes each) after 22 letters step back two bytes, four é after 23 one.
    String[][] cases = {
      {"abcdefghijklmnopqrstuv€€€", "Cannot index string with string (\"abcdefghijklmnopqrstuv...\")"},
      {"abcdefghijklmnopqrstuvwéééé", "Cannot index string with string (\"abcdefghijklmnopqrstuvw...\")"},
    };
    for (String[] c : cases) {
      Workspace workspace = ENGINE.newWorkspace();
      JqValue x = read(workspace, "\"x\"").root();
      JqFilterException e =
          assertThatExceptionOfType(JqFilterException.class)
              .as("run of .\"%s\" on \"x\"", c[0])
              .isThrownBy(() -> ENGINE.compile(".\"" + c[0] + "\"").run(x, NONE, new Results(), workspace))
              .actual();
      assertThat(((JqString) e.value()).value()).as("error text of .\"%s\"", c[0]).isEqualTo(c[1]);
    }
    // jq-ref -n -c '{"a":{"b":1}} | .a."b"' -> 1: a string key after a term.
    Workspace workspace = ENGINE.newWorkspace();
    JqValue ab = read(workspace, "{\"a\":{\"b\":1}}").root();
    Results results = new Results();
    ENGINE.compile(".a.\"b\"").run(ab, NONE, results, workspace);
    assertThat(results.values).as("results of .a.\"b\"").singleElement().matches(v -> v.toJson().equals("1"));
  }

  /** The one result of {@code text} on null, a number. */
  private static JqNumber number(String text) {
    Results results = new Results();
    ENGINE.compile(text).run(JqNull.NULL, NONE, results, ENGINE.newWorkspace());
    assertThat(results.values).as("results of %s", text).singleElement().isInstanceOf(JqNumber.class);
    return (JqNumber) results.values.getFirst();
  }
}
