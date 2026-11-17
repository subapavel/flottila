package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #32 at the public API seam: {@code Workspace.read} reads exactly one strict document (ADR-0015, ADR-0026)
 * and throws JqSyntaxException otherwise. The type is pinned everywhere; line and column only where
 * jq-ref 1.8.2 gives what the draft's rule gives; never the offset, and never a Stub's text (#22 Testing
 * Decisions). Exported types only.
 *
 * <p>Every jq-ref observation below was taken on 2026-11-11 with {@code printf -- '<input>' | jq-ref -c .},
 * where {@code \n}, {@code \r}, {@code \t}, {@code \f} and {@code \x..} stand for the real bytes (checked with
 * {@code od -c}). jq-ref prints "jq: parse error: <text>" and exits 5 on each rejection.
 *
 * <p>Each rejection has a witness: a near twin that jq-ref reads (rc 0) and {@code read} must read too, so a
 * reader that rejected everything would fail.
 */
class MalformedDocumentTest {

  static Stream<Arguments> truncated() {
    return Stream.of(
        // jq-ref: Unfinished JSON term at EOF at line 1, column 5
        argumentSet("{\"a\":", "{\"a\":", "{\"a\":1}", JqObject.class),
        // jq-ref: Unfinished JSON term at EOF at line 1, column 4
        argumentSet("[1,2", "[1,2", "[1,2]", JqArray.class),
        // jq-ref: Unfinished string at EOF at line 1, column 4
        argumentSet("[\"ab", "[\"ab", "[\"ab\"]", JqArray.class),
        // jq-ref: Invalid literal at EOF at line 1, column 3
        argumentSet("tru", "tru", "true", JqBool.class),
        // jq-ref: Unfinished JSON term at EOF at line 1, column 6
        argumentSet("{\"a\":1", "{\"a\":1", "{\"a\":1}", JqObject.class),
        // jq-ref: Invalid numeric literal at EOF at line 1, column 1
        argumentSet("-", "-", "-1", JqNumber.class));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("truncated")
  void truncatedDocumentIsMalformed(String malformed, String witness, Class<? extends JqValue> witnessKind) {
    assertMalformed(malformed, witness, witnessKind);
  }

  static Stream<Arguments> strayTokens() {
    return Stream.of(
        // jq-ref: Expected another array element at line 1, column 4
        argumentSet("[1,]", "[1,]", "[1]", JqArray.class),
        // jq-ref: Expected another key-value pair at line 1, column 8
        argumentSet("{\"a\":1,}", "{\"a\":1,}", "{\"a\":1}", JqObject.class),
        // jq-ref: Unmatched ']' at line 1, column 1
        argumentSet("]", "]", "[]", JqArray.class),
        // jq-ref: Objects must consist of key:value pairs at line 1, column 3
        argumentSet("[1}", "[1}", "[1]", JqArray.class),
        // jq-ref: Expected separator between values at line 1, column 7
        argumentSet("{\"a\" 1}", "{\"a\" 1}", "{\"a\":1}", JqObject.class),
        // jq-ref: Object keys must be strings at line 1, column 3
        argumentSet("{1:2}", "{1:2}", "{\"1\":2}", JqObject.class),
        // jq-ref: Expected separator between values at line 1, column 5
        argumentSet("[1 2]", "[1 2]", "[1,2]", JqArray.class),
        // jq-ref: Invalid string: control characters from U+0000 through U+001F must be escaped at line 1, column 4
        argumentSet("\"a\\x01\" (a raw 0x01)", "\"a\u0001\"", "\"a\\u0001\"", JqString.class),
        // jq-ref: Invalid numeric literal at line 1, column 6. The NUL row: rejected by jq-ref 1.8.2 here too.
        argumentSet("[1,\\x002] (a raw NUL)", "[1,\u00002]", "[1,2]", JqArray.class),
        // jq-ref: Invalid numeric literal at line 1, column 6 (ADR-0015: a form feed is no whitespace; TAB is)
        argumentSet("[1,\\f2] (a raw form feed)", "[1,\f2]", "[1,\t2]", JqArray.class),
        // jq-ref: Invalid escape at line 1, column 4
        argumentSet("\"\\q\"", "\"\\q\"", "\"\\n\"", JqString.class),
        // jq-ref: Invalid \\uXXXX escape at line 1, column 6
        argumentSet("\"\\u12\"", "\"\\u12\"", "\"\\u1234\"", JqString.class),
        // jq-ref: Invalid \\uXXXX escape at line 1, column 7 (the witness "\\u12ab" prints U+12AB, rc 0)
        argumentSet("\"\\u12x\"", "\"\\u12x\"", "\"\\u12ab\"", JqString.class),
        // jq-ref: Invalid characters in \\uXXXX escape at line 1, column 8. Four bytes follow \\u before the quote,
        // so a reader that skipped the hex check would close the string and accept it.
        argumentSet("\"\\u12xy\"", "\"\\u12xy\"", "\"\\u12ab\"", JqString.class),
        // jq-ref: Invalid literal at EOF at line 1, column 5
        argumentSet("truex", "truex", "true ", JqBool.class),
        // jq-ref: Invalid literal at line 1, column 5 (the witness [true] prints [true], rc 0)
        argumentSet("[tru]", "[tru]", "[true]", JqArray.class),
        // jq-ref: Invalid literal at line 1, column 6. As long as true, so a reader that skipped the letters would
        // accept it.
        argumentSet("[trux]", "[trux]", "[true]", JqArray.class));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("strayTokens")
  void strayTokenIsMalformed(String malformed, String witness, Class<? extends JqValue> witnessKind) {
    assertMalformed(malformed, witness, witnessKind);
  }

  /** jq-ref reads several values from one input; {@code read} reads exactly one (ADR-0026, #22 story 30). */
  static Stream<Arguments> secondValues() {
    return Stream.of(
        // jq-ref: prints 1 and 2, rc 0
        argumentSet("1 2", "1 2", "1 ", JqNumber.class),
        // jq-ref: prints {} and {}, rc 0
        argumentSet("{}{}", "{}{}", "{} ", JqObject.class),
        // jq-ref: prints [], then Invalid numeric literal at EOF at line 1, column 3, rc 5
        argumentSet("[]x", "[]x", "[]\n", JqArray.class),
        // jq-ref: prints "a" and "b", rc 0
        argumentSet("\"a\"\"b\"", "\"a\"\"b\"", "\"a\"", JqString.class),
        // jq-ref: prints 1 and "a", rc 0
        argumentSet("1\"a\"", "1\"a\"", "1", JqNumber.class));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("secondValues")
  void secondTopLevelValueIsMalformed(String malformed, String witness, Class<? extends JqValue> witnessKind) {
    assertMalformed(malformed, witness, witnessKind);
  }

  /** ADR-0015 accepts these lenient tokens; #22 keeps them malformed until spec 5, whose ticket flips this test. */
  static Stream<Arguments> lenientNumbers() {
    return Stream.of(
        // jq-ref: prints 1, rc 0
        argumentSet("+1", "+1", "1", JqNumber.class),
        // jq-ref: prints 0.5, rc 0
        argumentSet(".5", ".5", "0.5", JqNumber.class),
        // jq-ref: prints 7, rc 0
        argumentSet("007", "007", "7", JqNumber.class),
        // jq-ref: prints 1, rc 0
        argumentSet("1.", "1.", "1.0", JqNumber.class),
        // jq-ref: prints 2E+3, rc 0
        argumentSet("2.e3", "2.e3", "2.0e3", JqNumber.class),
        // jq-ref: prints null, rc 0
        argumentSet("NaN", "NaN", "null", JqNull.class),
        // jq-ref: prints -1.7976931348623157e+308, rc 0
        argumentSet("-Infinity", "-Infinity", "-1e308", JqNumber.class));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("lenientNumbers")
  void lenientNumberIsMalformedUntilSpec5(String malformed, String witness, Class<? extends JqValue> witnessKind) {
    assertMalformed(malformed, witness, witnessKind);
  }

  /**
   * ADR-0026: {@code read} reads exactly one document, so empty and whitespace-only bytes are malformed. jq-ref
   * prints nothing and exits 0 on both (zero documents): that rule belongs to the input-stream reader (#38).
   */
  static Stream<Arguments> emptyInputs() {
    return Stream.of(
        // jq-ref: no output, rc 0
        argumentSet("empty", "", "0", JqNumber.class),
        // jq-ref: no output, rc 0
        argumentSet("SP LF TAB", " \n\t", " \n\t0", JqNumber.class));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("emptyInputs")
  void emptyInputIsMalformed(String malformed, String witness, Class<? extends JqValue> witnessKind) {
    assertMalformed(malformed, witness, witnessKind);
  }

  /**
   * The observed subset where jq-ref's line and column equal the draft's rule (k = the detecting byte + 1, or the
   * length at EOF; line = 1 + LFs before k; column = k - 1 - the last LF before k, or k without one). The rows
   * where they differ are left out: {@code {"a" 1}} (jq-ref 7, rule 6), {@code {1:2}} (3, 2), {@code [1 2]} (5,
   * 4), {@code "a\x01"} (4, 3), {@code [1,\x002]} and {@code [1,\f2]} (6, 4), {@code "\q"} (4, 3), {@code "\\u12x"} (7,
   * 6), {@code "\\u12xy"} (8, 6), {@code [trux]} (6, 5).
   */
  static Stream<Arguments> positions() {
    return Stream.of(
        // jq-ref: Unfinished JSON term at EOF at line 1, column 5
        argumentSet("{\"a\": at EOF", "{\"a\":", 1, 5),
        // jq-ref: Unfinished JSON term at EOF at line 1, column 4
        argumentSet("[1,2 at EOF", "[1,2", 1, 4),
        // jq-ref: Unfinished string at EOF at line 1, column 4
        argumentSet("[\"ab at EOF", "[\"ab", 1, 4),
        // jq-ref: Unfinished JSON term at EOF at line 2, column 0
        argumentSet("[ LF at EOF", "[\n", 2, 0),
        // jq-ref: Unfinished JSON term at EOF at line 2, column 0
        argumentSet("{\"a\": CR LF at EOF", "{\"a\":\r\n", 2, 0),
        // jq-ref: Expected another array element at line 1, column 4
        argumentSet("[1,]", "[1,]", 1, 4),
        // jq-ref: Expected another key-value pair at line 1, column 8
        argumentSet("{\"a\":1,}", "{\"a\":1,}", 1, 8),
        // jq-ref: Objects must consist of key:value pairs at line 1, column 3
        argumentSet("[1}", "[1}", 1, 3),
        // jq-ref: Unmatched ']' at line 1, column 1
        argumentSet("]", "]", 1, 1),
        // jq-ref: Objects must consist of key:value pairs at line 1, column 10
        argumentSet("{\"a\":[1,2}", "{\"a\":[1,2}", 1, 10),
        // jq-ref: Expected another array element at line 3, column 1
        argumentSet("[1, LF 2, LF ]", "[1,\n2,\n]", 3, 1),
        // jq-ref: Invalid literal at EOF at line 1, column 3
        argumentSet("tru at EOF", "tru", 1, 3),
        // jq-ref: Unfinished JSON term at EOF at line 1, column 6
        argumentSet("{\"a\":1 at EOF", "{\"a\":1", 1, 6),
        // jq-ref: Invalid numeric literal at EOF at line 1, column 1
        argumentSet("- at EOF", "-", 1, 1),
        // jq-ref: Invalid \\uXXXX escape at line 1, column 6
        argumentSet("\"\\u12\"", "\"\\u12\"", 1, 6),
        // jq-ref: Invalid literal at EOF at line 1, column 5
        argumentSet("truex", "truex", 1, 5),
        // jq-ref: Invalid literal at line 1, column 5
        argumentSet("[tru]", "[tru]", 1, 5));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("positions")
  void malformedPositionIsJqRefs(String malformed, int line, int column) {
    Workspace workspace = newWorkspace();
    assertThatThrownBy(() -> read(workspace, malformed))
        .as("read(%s)", printable(malformed))
        .isInstanceOfSatisfying(
            JqSyntaxException.class,
            e -> {
              assertThat(e.line()).as("line of %s", printable(malformed)).isEqualTo(line);
              assertThat(e.column()).as("column of %s", printable(malformed)).isEqualTo(column);
            });
  }

  /**
   * #35 K2 (ADR-0015 "Strings and BOM"): a high surrogate escape not followed by a low one is malformed, detected at
   * the string's closing quote; in spec 1 the error is the malformed-input Stub (grilling item 7), so only the type and
   * jq-ref's line and column are pinned. jq-ref 1.8.2, observed 2026-11-12 (backslash-u written \\u):
   * {@code "\\ud800x"} -> "Invalid \\uXXXX\\uXXXX surrogate pair escape at line 1, column 9"; {@code "\\ud800"} ->
   * column 8; {@code "\\ud800\\u0041"} -> column 14; {@code "\\ud800\\ud800\\udc00"} -> column 20; {@code
   * ["\\ud800",1]} and {@code {"\\ud800":1}} -> column 9 (rc 5 each). The witness {@code "\\ud800\\udc00x"} prints
   * {@code "𐀀x"} (code points [65536,120], rc 0).
   */
  static Stream<Arguments> unpairedHighSurrogates() {
    return Stream.of(
        argumentSet("\"\\\\ud800x\"", "\"\\ud800x\"", 9),
        argumentSet("\"\\\\ud800\" at the closing quote", "\"\\ud800\"", 8),
        argumentSet("\"\\\\ud800\\\\u0041\"", "\"\\ud800\\u0041\"", 14),
        argumentSet("\"\\\\ud800\\\\ud800\\\\udc00\"", "\"\\ud800\\ud800\\udc00\"", 20),
        argumentSet("[\"\\\\ud800\",1]", "[\"\\ud800\",1]", 9),
        argumentSet("{\"\\\\ud800\":1}", "{\"\\ud800\":1}", 9));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("unpairedHighSurrogates")
  void unpairedHighSurrogateIsMalformedAtTheClosingQuote(String malformed, int column) {
    Workspace workspace = newWorkspace();
    assertThatExceptionOfType(JqSyntaxException.class)
        .as("read(%s)", printable(malformed))
        .isThrownBy(() -> read(workspace, malformed))
        .satisfies(
            e -> {
              assertThat(e.line()).as("line of %s", printable(malformed)).isEqualTo(1);
              assertThat(e.column()).as("column of %s", printable(malformed)).isEqualTo(column);
            });
    // Witness: the paired escape reads, so the reader does not reject every high surrogate.
    JqString paired = kind(JqString.class, read(workspace, "\"\\ud800\\udc00x\"").root(), "\"\\ud800\\udc00x\"");
    assertThat(paired.value()).as("value() of the witness").isEqualTo("𐀀x");
  }

  /**
   * The document is a slice of a larger array, so line and column count from the slice's first byte, never from
   * the array's (ADR-0015: a writable byte[] heap segment is read in place at its address). jq-ref 1.8.2 on the
   * slice's bytes alone, observed 2026-11-11 with {@code printf -- '<doc>' | jq-ref -c .}:
   * {@code [1,]} -> "Expected another array element at line 1, column 4"; {@code [1,\n2,\n]} -> "Expected another
   * array element at line 3, column 1"; {@code [1,2} -> "Unfinished JSON term at EOF at line 1, column 4".
   */
  static Stream<Arguments> slicedPositions() {
    return Stream.of(
        // An LF before the slice: counting it gives line 2; counting columns from the array gives 6.
        argumentSet("x LF, then [1,]", "x\n", "[1,]", "", 1, 4),
        // LFs inside the slice count, the one before it does not.
        argumentSet("x LF, then [1, LF 2, LF ]", "x\n", "[1,\n2,\n]", "", 3, 1),
        // No LF anywhere: the column counts from the slice's first byte.
        argumentSet("xyz, then [1,]", "xyz", "[1,]", "", 1, 4),
        // EOF is the slice's end, not the array's: the ']' after it is not read.
        argumentSet("x LF, then [1,2 with ]]] after the slice", "x\n", "[1,2", "]]]", 1, 4));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("slicedPositions")
  void malformedPositionCountsFromTheSegmentStart(String before, String malformed, String after, int line, int column) {
    byte[] array = (before + malformed + after).getBytes(UTF_8);
    MemorySegment slice = MemorySegment.ofArray(array).asSlice(before.length(), malformed.length());
    Workspace workspace = newWorkspace();
    assertThatThrownBy(() -> workspace.read(slice))
        .as("read(%s) at %d of %s", printable(malformed), before.length(), printable(before + malformed + after))
        .isInstanceOfSatisfying(
            JqSyntaxException.class,
            e -> {
              assertThat(e.line()).as("line").isEqualTo(line);
              assertThat(e.column()).as("column").isEqualTo(column);
            });
  }

  // ADR-0026 (Q4 of #32) and ADR-0015:46-51: the reader enforces jq's parse depth of 10 000 with jq's text.
  // jq-ref 1.8.2 rejects a '[' or '{' when its parser stack already holds 10 000 entries: one per open array,
  // one per open object, and one more for a key whose value is still open. Observed 2026-11-11, each file
  // written by a bash loop, with `jq-ref -c length < file` (L) or `jq-ref -c '[paths] | length' < file` (P);
  // every "Exceeds" row has rc 5:
  //   L  '[' x10000, ']' x10000                     -> 1, rc 0
  //   L  '[' x10001, ']' x10001                     -> Exceeds depth limit for parsing at line 1, column 10001
  //   L  '{"a":' x5000, '1', '}' x5000              -> 1, rc 0
  //   L  '{"a":' x5001, '1', '}' x5001              -> Exceeds depth limit for parsing at line 1, column 25001
  //   L  '{"a":' x5000, '{}', '}' x5000             -> Exceeds depth limit for parsing at line 1, column 25001
  //   P  '[' x9999, '{}', ']' x9999                 -> 9999, rc 0
  //   P  '[' x9999, '{"a":1}', ']' x9999            -> 10000, rc 0
  //   P  '[' x9999, '{"a":[]}', ']' x9999           -> Exceeds depth limit for parsing at line 1, column 10005
  //   L  '[' x5000, LF, '[' x5001, ']' x10001       -> Exceeds depth limit for parsing at line 2, column 5001
  @Test
  void depthLimitIsJqRefs() {
    Workspace workspace = newWorkspace();

    JqValue deepest = descend(read(workspace, "[".repeat(10_000) + "]".repeat(10_000)).root(), 9_999);
    assertThat(kind(JqArray.class, deepest, "the 10 000th array").length()).as("the 10 000th array is empty").isZero();
    assertDepthError(workspace, "[".repeat(10_001) + "]".repeat(10_001), 1, 10_001);

    JqValue inner = read(workspace, "{\"a\":".repeat(5_000) + "1" + "}".repeat(5_000)).root();
    for (int level = 1; level <= 5_000; level++) {
      inner = kind(JqObject.class, inner, "object " + level).get("a");
    }
    assertThat(kind(JqNumber.class, inner, "the value inside 5 000 objects").longValue()).as("inside 5 000 objects").isEqualTo(1L);
    assertDepthError(workspace, "{\"a\":".repeat(5_001) + "1" + "}".repeat(5_001), 1, 25_001);
    assertDepthError(workspace, "{\"a\":".repeat(5_000) + "{}" + "}".repeat(5_000), 1, 25_001);

    // An object counts one until a key's value is open.
    JqValue emptyObject = descend(read(workspace, "[".repeat(9_999) + "{}" + "]".repeat(9_999)).root(), 9_999);
    assertThat(kind(JqObject.class, emptyObject, "{} inside 9 999 arrays").size()).as("{} inside 9 999 arrays").isZero();
    JqValue oneKey = descend(read(workspace, "[".repeat(9_999) + "{\"a\":1}" + "]".repeat(9_999)).root(), 9_999);
    JqObject keyed = kind(JqObject.class, oneKey, "{\"a\":1} inside 9 999 arrays");
    assertThat(kind(JqNumber.class, keyed.get("a"), ".a inside 9 999 arrays").longValue()).as(".a inside 9 999 arrays").isEqualTo(1L);
    assertDepthError(workspace, "[".repeat(9_999) + "{\"a\":[]}" + "]".repeat(9_999), 1, 10_005);

    assertDepthError(workspace, "[".repeat(5_000) + "\n" + "[".repeat(5_001) + "]".repeat(10_001), 2, 5_001);
  }

  /** Follows element 0 {@code times} times from {@code value}. */
  private static JqValue descend(JqValue value, int times) {
    JqValue current = value;
    for (int level = 1; level <= times; level++) {
      current = kind(JqArray.class, current, "array " + level).get(0);
    }
    return current;
  }

  /** jq's text without the command line's "jq: parse error: " frame (ADR-0015 "Errors"); not a Stub's text. */
  private static void assertDepthError(Workspace workspace, String json, int line, int column) {
    String expected = "Exceeds depth limit for parsing at line " + line + ", column " + column;
    assertThatThrownBy(() -> read(workspace, json))
        .as("depth of a %d-byte document", json.length())
        .isInstanceOfSatisfying(
            JqSyntaxException.class,
            e -> {
              assertThat(e.getMessage()).as("message").isEqualTo(expected);
              assertThat(e.line()).as("line").isEqualTo(line);
              assertThat(e.column()).as("column").isEqualTo(column);
            });
  }

  private static void assertMalformed(String malformed, String witness, Class<? extends JqValue> witnessKind) {
    Workspace workspace = newWorkspace();
    assertThatThrownBy(() -> read(workspace, malformed)).as("read(%s)", printable(malformed)).isInstanceOf(JqSyntaxException.class);
    assertThat(read(workspace, witness).root()).as("witness read(%s)", printable(witness)).isInstanceOf(witnessKind);
  }

  /** The input with its control bytes spelled out, for assertion messages. */
  private static String printable(String json) {
    StringBuilder out = new StringBuilder("'");
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c < 0x20) {
        out.append(String.format("\\x%02x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.append('\'').toString();
  }
}
