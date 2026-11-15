package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Fixtures.utf8;
import static io.flottila.mapping.mapping.LoadResults.errorAtPointer;
import static io.flottila.mapping.mapping.LoadResults.loaded;
import static io.flottila.mapping.mapping.LoadResults.refused;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Before the two validation stages (ADR-0011, "Stage 1 and 2 codes"): malformed JSON, a BOM, a
 * duplicate key or bad UTF-8 is a transport error, and a newer {@code formatVersion} is refused
 * with one diagnostic.
 */
class MappingModelLoadTest {

  /** A valid document with a duplicate-free table; the malformed cases each break one thing. */
  static final String VALID =
      """
      {
        "formatVersion": 1,
        "source": {
          "bundle": [
            "order.xsd"
          ],
          "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "root": {
            "element": "Order"
          }
        },
        "target": {
          "bundle": [
            "invoice.xsd"
          ],
          "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
          "root": {
            "element": "Invoice"
          }
        },
        "targetFieldMappings": {
          "/Invoice/Currency": {
            "boxes": {
              "a1": {
                "field": "/Order/Currency",
                "kind": "source"
              },
              "b1": {
                "fn": "fixValues",
                "inputs": [
                  "a1"
                ],
                "kind": "function",
                "params": {
                  "table": {
                    "CHF": "CHF",
                    "euro": "EUR"
                  }
                }
              }
            },
            "id": "t1",
            "result": "b1"
          }
        }
      }
      """;

  @Test
  void theValidDocumentTheMalformedCasesBreakLoads() throws Exception {
    assertThat(loaded(MappingModelFormat.load(utf8(VALID))).formatVersion()).isEqualTo(1);
  }

  static Stream<Arguments> malformed() {
    byte[] valid = utf8(VALID);
    return Stream.of(
        Arguments.of("empty input", new byte[0]),
        Arguments.of("truncated", utf8(VALID.substring(0, VALID.length() - 3))),
        Arguments.of(
            "trailing comma", utf8(VALID.replace("\"result\": \"b1\"", "\"result\": \"b1\","))),
        Arguments.of("trailing content", utf8(VALID + "{}\n")),
        Arguments.of("single-quoted string", utf8(VALID.replace("\"t1\"", "'t1'"))),
        Arguments.of("BOM", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, valid)),
        Arguments.of(
            "duplicate top-level key",
            utf8(
                VALID.replace(
                    "\"formatVersion\": 1,", "\"formatVersion\": 1,\n\"formatVersion\": 1,"))),
        Arguments.of(
            "duplicate box id in one target field mapping",
            utf8(
                VALID.replace(
                    "\"a1\": {",
                    "\"a1\": {\"field\": \"/Order/Currency\", \"kind\": \"source\"},\n\"a1\": {"))),
        Arguments.of(
            "duplicate table key",
            utf8(VALID.replace("\"CHF\": \"CHF\",", "\"CHF\": \"CHF\",\n\"CHF\": \"CHF\","))),
        Arguments.of("invalid byte 0xFF in a string", badUtf8("CH", new byte[] {(byte) 0xFF}, "F")),
        Arguments.of("truncated two-byte sequence", badUtf8("CH", new byte[] {(byte) 0xC3}, "F")),
        Arguments.of(
            "overlong encoding of '/'", badUtf8("CH", new byte[] {(byte) 0xC0, (byte) 0xAF}, "F")),
        Arguments.of(
            "UTF-8 encoded surrogate",
            badUtf8("CH", new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, "F")),
        Arguments.of("lone continuation byte", badUtf8("CH", new byte[] {(byte) 0x80}, "F")),
        // RFC 8259 rules json-sKema 0.32.0's own parser does not enforce (StrictJsonReader)
        Arguments.of("raw control character U+0001 in a string", inEur("E\u0001UR")),
        Arguments.of("raw control character U+001F in a string", inEur("E\u001fUR")),
        Arguments.of("unknown escape \\q", inEur("E\\qUR")),
        Arguments.of("bad hex digit in a \\u escape", inEur("E\\u12g4UR")),
        Arguments.of("misspelt literal tru", asResult("tru")),
        Arguments.of("misspelt literal nul", asResult("nul")),
        Arguments.of("misspelt literal fals", asResult("fals")),
        Arguments.of(
            "trailing comma in an object",
            utf8(VALID.replace("\"euro\": \"EUR\"", "\"euro\": \"EUR\","))),
        Arguments.of(
            "trailing comma in an array", utf8(VALID.replace("\"order.xsd\"", "\"order.xsd\","))),
        Arguments.of(
            "leading zero in formatVersion",
            utf8(VALID.replace("\"formatVersion\": 1,", "\"formatVersion\": 01,"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformed")
  void loadThrowsMalformedOnSyntaxBomDuplicateKeyOrBadUtf8(String why, byte[] json) {
    assertThatThrownBy(() -> MappingModelFormat.load(json))
        .as(why)
        .isInstanceOf(MalformedJsonException.class);
  }

  /**
   * The reader stops at its depth limit (StrictJsonReader.MAX_DEPTH, 64; a mapping model nests
   * seven) instead of recursing into the document: far beyond the limit it is a transport error,
   * never a {@link StackOverflowError}.
   */
  @Test
  void loadThrowsMalformedNotStackOverflowOnNestingFarBeyondTheDepthLimit() {
    int levels = 100_000;
    byte[] deep = utf8(VALID.replace("\"t1\"", "[".repeat(levels) + "]".repeat(levels)));

    assertThatThrownBy(() -> MappingModelFormat.load(deep))
        .isInstanceOf(MalformedJsonException.class);
  }

  /** RFC 8259 §2: whitespace before the document and around a name separator is insignificant. */
  @Test
  void whitespaceBeforeTheDocumentAndAroundAColonLoadsTheSameModel() throws Exception {
    MappingModel model = loaded(MappingModelFormat.load(utf8(VALID)));

    assertThat(loaded(MappingModelFormat.load(utf8(" \t\r\n\n" + VALID)))).isEqualTo(model);
    assertThat(
            loaded(
                MappingModelFormat.load(
                    utf8(VALID.replace("\"formatVersion\": 1", "\"formatVersion\" \t: 1")))))
        .isEqualTo(model);
  }

  /** An empty object or array with whitespace inside is well-formed JSON, not a transport error. */
  @Test
  void anEmptyObjectOrArrayWithWhitespaceInsideIsWellFormed() throws Exception {
    // a newer format is refused unvalidated, so the members may have any shape
    byte[] json = utf8("{\"formatVersion\": 2, \"object\": { \n }, \"array\": [ \t ]}");

    assertThat(refused(MappingModelFormat.load(json)))
        .containsExactly(errorAtPointer("format-version-unsupported", "/formatVersion"));
  }

  /** A syntax error names its line and column, counted after the newlines before it. */
  @Test
  void aSyntaxErrorIsReportedAtItsLineAndColumn() {
    String broken = VALID.replace("\"id\": \"t1\"", "\"id\": 't1'");
    String[] lines = broken.split("\n", -1);
    int line = 0;
    while (!lines[line].contains("'t1'")) {
      line++;
    }
    int column = lines[line].indexOf("'t1'") + 1;

    assertThatThrownBy(() -> MappingModelFormat.load(utf8(broken)))
        .isInstanceOf(MalformedJsonException.class)
        .hasMessageEndingWith(" at line " + (line + 1) + ", column " + column);
  }

  @ParameterizedTest(name = "formatVersion {0}")
  @ValueSource(ints = {2, 10})
  void loadRefusesNewerFormatVersionWithThatDiagnosticAlone(int version) throws Exception {
    String newer = VALID.replace("\"formatVersion\": 1,", "\"formatVersion\": " + version + ",");
    // a newer format may have any shape; nothing of it is validated
    String newerAndForeign =
        """
        {
          "formatVersion": %d,
          "layout": {"zoom": 2},
          "source": "order.xsd",
          "targetFieldMappings": {"/Invoice/Number": {"id": "not an id", "boxes": []}}
        }
        """
            .formatted(version);

    assertThat(refused(MappingModelFormat.load(utf8(newer))))
        .containsExactly(errorAtPointer("format-version-unsupported", "/formatVersion"));
    assertThat(refused(MappingModelFormat.load(utf8(newerAndForeign))))
        .containsExactly(errorAtPointer("format-version-unsupported", "/formatVersion"));
  }

  @Test
  void loadValidatesFormatVersionOneInsteadOfRefusingIt() throws Exception {
    String foreign =
        """
        {
          "formatVersion": 1,
          "layout": {"zoom": 2},
          "source": "order.xsd",
          "targetFieldMappings": {"/Invoice/Number": {"id": "not an id", "boxes": []}}
        }
        """;

    assertThat(refused(MappingModelFormat.load(utf8(foreign))))
        .isNotEmpty()
        .noneMatch(d -> d.contains("format-version-unsupported"));
  }

  /**
   * {@link #VALID} with the table value {@code "EUR"} spelt as the JSON string body {@code body}.
   */
  private static byte[] inEur(String body) {
    return utf8(VALID.replace("\"EUR\"", "\"" + body + "\""));
  }

  /**
   * {@link #VALID} with {@code word} as the value of {@code result}, followed by a line break: a
   * reader that skipped the literal's length unchecked would read the document as well-formed.
   */
  private static byte[] asResult(String word) {
    return utf8(VALID.replace("\"result\": \"b1\"", "\"result\": " + word));
  }

  /** {@link #VALID} with {@code bad} spliced into the table key {@code "CHF"}. */
  private static byte[] badUtf8(String before, byte[] bad, String after) {
    String marker = "\"" + before + after + "\": \"CHF\"";
    int at = VALID.indexOf(marker);
    assertThat(at).as("marker in the valid document").isNotNegative();
    byte[] head = VALID.substring(0, at + 1 + before.length()).getBytes(UTF_8);
    byte[] tail = VALID.substring(at + 1 + before.length()).getBytes(UTF_8);
    return concat(concat(head, bad), tail);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(a);
    out.writeBytes(b);
    return out.toByteArray();
  }
}
