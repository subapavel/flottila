package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The N of jq's {@code (at <file>:N)} from the input bytes alone (#34 design section 9, GLOSSARY "Read block" and
 * "Completing byte", F-25): the read blocks that end in LF, up to the block holding the completing byte. A read block
 * is the bytes through the next LF, or 4091 bytes plus the rest of a UTF-8 character cut there. B is a 1-based byte
 * position. Every N is jq-ref 1.8.2's: the {@code field/line-*} cases recorded in {@code diff/field.rec}, the others
 * observed on 2026-11-11 with {@code .a} on the same bytes ({@code jq: error (at <stdin>:N): ...}, rc 5).
 */
class ErrorLineTest {

  /** The bytes of the parts in order: a String part is its UTF-8, a byte[] part as it is. */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object p : parts) out.writeBytes(p instanceof byte[] b ? b : ((String) p).getBytes(UTF_8));
    return out.toByteArray();
  }

  private static String xs(int n) {
    return "x".repeat(n);
  }

  private static String spaces(int n) {
    return " ".repeat(n);
  }

  private static Arguments n(String name, int expected, byte[] input) {
    return Arguments.argumentSet(name, input, expected);
  }

  static Stream<Arguments> recorded() {
    return Stream.of(
        // diff/field.rec, field/line-*.
        n("line-no-lf [1]", 0, bytes("[1]")),
        n("line-lf [1] LF", 1, bytes("[1]\n")),
        n("line-inner-lfs [ LF 1 LF ]", 2, bytes("[\n1\n]")),
        n("line-trailing-lfs [1] LF LF LF", 1, bytes("[1]\n\n\n")),
        n("line-leading-lfs LF LF [1]", 2, bytes("\n\n[1]")),
        n("line-crlf CR LF [ CR LF 1] CR LF", 3, bytes("\r\n[\r\n1]\r\n")),
        n("line-number-eof 1", 0, bytes("1")),
        n("line-cut-before: ] at B4091, LF", 0, bytes("[\"", xs(4087), "\"]\n")),
        n("line-cut-after: ] at B4092, LF", 1, bytes("[\"", xs(4088), "\"]\n")),
        n("line-number-late: 1 at B4091, LF", 1, bytes(spaces(4090), "1\n")),
        n("line-literal-late: true ending at B4091, LF", 1, bytes(spaces(4087), "true\n")),
        n("line-string-cut: closing quote at B4091, LF", 0, bytes("\"", xs(4089), "\"\n")),
        n("line-utf8-cut: C3 A9 at B4091..4092, ] at B8183, LF", 0, bytes("[\"", xs(4088), "é", xs(4089), "\"]\n")),
        // Whitespace after ] at B4091: the completing byte is still ], so the LF at B4093 is in a later block.
        n("line-cut-space: ] at B4091, SP, LF", 0, bytes("[\"", xs(4087), "\"] \n")),
        n("line-cut-tab: ] at B4091, TAB, LF", 0, bytes("[\"", xs(4087), "\"]\t\n")),
        n("line-cut-cr: ] at B4091, CR, LF", 0, bytes("[\"", xs(4087), "\"]\r\n")));
  }

  static Stream<Arguments> observed() {
    return Stream.of(
        // The second and third cut, at 2 x 4091 and 3 x 4091: blocks [1, 4091], [4092, 8182], [8183, 12273].
        n("] at B8182, LF", 0, bytes("[", spaces(8180), "]\n")),
        n("] at B8183, LF", 1, bytes("[", spaces(8181), "]\n")),
        n("] at B8181, LF at B8182", 1, bytes("[", spaces(8179), "]\n")),
        n("] at B12273, LF", 0, bytes("[", spaces(12271), "]\n")),
        n("] at B12274, LF", 1, bytes("[", spaces(12272), "]\n")),
        // An LF before the cut starts the count of 4091 after it: blocks [1, 10], [11, 4101].
        n("LF at B10, ] at B4101, LF", 1, bytes("[", spaces(8), "\n", spaces(4090), "]\n")),
        n("LF at B10, ] at B4100, LF at B4101", 2, bytes("[", spaces(8), "\n", spaces(4089), "]\n")),
        // A character that ends at B4091 is not cut, so the next block starts at B4092.
        n("C3 A9 at B4090..4091, ] at B8182, LF", 0, bytes("[\"", xs(4087), "é", xs(4089), "\"]\n")),
        n("C3 A9 at B4090..4091, ] at B8183, LF", 1, bytes("[\"", xs(4087), "é", xs(4090), "\"]\n")),
        // Without F-25 the block would end at B4091 and ] at B8183 would sit in a block of its own after an LF-less one.
        n("C3 A9 at B4091..4092, ] at B8182, LF", 1, bytes("[\"", xs(4088), "é", xs(4088), "\"]\n")),
        // Three and four bytes: the block takes the rest of the character.
        n("E2 82 AC at B4090..4092, ] at B8183, LF", 0, bytes("[\"", xs(4087), "€", xs(4089), "\"]\n")),
        n("E2 82 AC at B4091..4093, ] at B8184, LF", 0, bytes("[\"", xs(4088), "€", xs(4089), "\"]\n")),
        n("F0 9D 84 9E at B4089..4092, ] at B8183, LF", 0, bytes("[\"", xs(4086), "𝄞", xs(4089), "\"]\n")),
        n("F0 9D 84 9E at B4091..4094, ] at B8185, LF", 0, bytes("[\"", xs(4088), "𝄞", xs(4089), "\"]\n")),
        // The LF at B8185 is the last byte of the second block, which then ends in LF.
        n("F0 9D 84 9E at B4091..4094, ] at B8184, LF", 1, bytes("[\"", xs(4088), "𝄞", xs(4088), "\"]\n")),
        // Two cut characters: blocks [1, 4092] and [4093, 8184], so ] at B12275 is in the third block.
        n("C3 A9 at B4091 and at B8183, ] at B12275, LF", 0, bytes("[\"", xs(4088), "é", xs(4090), "é", xs(4089), "\"]\n")),
        // Malformed UTF-8 at the cut: a lead byte alone still takes the byte after it; a lone continuation byte does not.
        n("lone C3 at B4091, ] at B8183, LF", 0, bytes("[\"", xs(4088), new byte[] {(byte) 0xc3}, xs(4090), "\"]\n")),
        n("lone 80 at B4091, ] at B8182, LF", 0, bytes("[\"", xs(4088), new byte[] {(byte) 0x80}, xs(4089), "\"]\n")),
        // The completing byte of a number or literal is the byte after it, or the end of the input.
        n("LF LF 1", 2, bytes("\n\n1")),
        n("1 LF LF", 1, bytes("1\n\n")),
        n("SP 1", 0, bytes(" 1")),
        n("true", 0, bytes("true")),
        n("1 at B4091, end of input", 0, bytes(spaces(4090), "1")),
        n("[1] SP LF", 1, bytes("[1] \n")),
        n("[1] TAB LF", 1, bytes("[1]\t\n")),
        n("\"s\" LF", 1, bytes("\"s\"\n")));
  }

  @ParameterizedTest(name = "{argumentSetName} -> {1}")
  @MethodSource({"recorded", "observed"})
  void nCountsTheReadBlocksEndingInLf(byte[] input, int expected) {
    assertThat(ErrorLine.of(input)).as("N of %d bytes", input.length).isEqualTo(expected);
  }
}
