package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A filter's positions and error blocks (ADR-0031, GLOSSARY "Error block"): offsets are UTF-8 bytes; the line is the
 * LFs before an offset plus 1, the column the bytes since the line start plus 1; a block is {@code jq: error: }, the
 * message, the position of the span's start, the start's line indented by four spaces and carets under the span that
 * stop at the line end. Each expected block is jq-ref 1.8.2's, recorded in diff/literal.rec under the case named.
 */
class FilterTextTest {

  @Test
  void caretsStopAtLineEnd() {
    // literal/error-backslash-lf: the escape run is the backslash and the LF, bytes [1, 3) of "\<LF>"; one caret.
    String text = "\"\\\n\"";
    assertThat(new FilterText(text).block("Invalid escape at line 2, column 1 (while parsing '\"\\\n\"')", 1, 3))
        .as("block of the run backslash LF")
        .isEqualTo(
            "jq: error: Invalid escape at line 2, column 1 (while parsing '\"\\\n\"') at <top-level>, line 1, column 2:\n"
                + "    \"\\\n"
                + "     ^");

    // Witness, literal/error-backslash-crlf: a CR is no line end, so the run backslash CR, bytes [1, 3) of
    // "\<CR><LF>", gets both carets and the source line keeps the CR.
    String crlf = "\"\\\r\n\"";
    assertThat(new FilterText(crlf).block("Invalid escape at line 1, column 4 (while parsing '\"\\\r\"')", 1, 3))
        .as("block of the run backslash CR")
        .isEqualTo(
            "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\\r\"') at <top-level>, line 1, column 2:\n"
                + "    \"\\\r\n"
                + "     ^^");
  }

  @Test
  void anErrorBlockCountsColumnsAndCaretIndentInBytes() {
    // literal/error-byte-column: in "é\v" the run \v is bytes [3, 5), é being two bytes: column 4, seven spaces.
    String text = "\"é\\v\"";
    FilterText filter = new FilterText(text);
    assertThat(filter.block("Invalid escape at line 1, column 4 (while parsing '\"\\v\"')", 3, 5))
        .as("block of \\v after é")
        .isEqualTo(
            "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\v\"') at <top-level>, line 1, column 4:\n"
                + "    \"é\\v\"\n"
                + "       ^^");
    assertThat(filter.line(3)).as("line of byte 3").isEqualTo(1);
    assertThat(filter.column(3)).as("column of byte 3").isEqualTo(4);
  }

  @Test
  void anErrorBlockShowsOnlyTheLineOfItsStart() {
    // literal/error-line-3: ".", "|" and "a\v" on three lines; the run \v is bytes [6, 8).
    String text = ".\n|\n\"a\\v\"";
    FilterText filter = new FilterText(text);
    assertThat(filter.block("Invalid escape at line 1, column 4 (while parsing '\"\\v\"')", 6, 8))
        .as("block of \\v on line 3")
        .isEqualTo(
            "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\v\"') at <top-level>, line 3, column 3:\n"
                + "    \"a\\v\"\n"
                + "      ^^");
    assertThat(filter.line(6)).as("line of byte 6").isEqualTo(3);
    assertThat(filter.column(6)).as("column of byte 6").isEqualTo(3);
    // ADR-0031's units at a line start: byte 4, the quote after the second LF, is line 3, column 1.
    assertThat(filter.line(4)).as("line of byte 4").isEqualTo(3);
    assertThat(filter.column(4)).as("column of byte 4").isEqualTo(1);
  }
}
