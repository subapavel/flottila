package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The read block at a cut inside a character, for every kind of byte there (F-25): which bytes take more bytes after
 * them, how continuation bytes before the cut count, and that an LF the completion takes in does not count. Every N
 * is jq-ref 1.8.2's, observed 2026-11-11 with {@code jq-ref -c .a} (or as noted) on the same bytes ({@code jq: error (at
 * <stdin>:N): ...}, rc 5). B is a 1-based byte position.
 */
class ErrorLineCutTest {

  /** The bytes of the parts in order: a String part is its UTF-8, a byte[] part as it is. */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object p : parts) {
      out.writeBytes(p instanceof byte[] b ? b : ((String) p).getBytes(UTF_8));
    }
    return out.toByteArray();
  }

  private static byte[] of(int... bytes) {
    byte[] b = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      b[i] = (byte) bytes[i];
    }
    return b;
  }

  private static byte[] continuations(int n) {
    byte[] b = new byte[n];
    Arrays.fill(b, (byte) 0x80);
    return b;
  }

  private static String xs(int n) {
    return "x".repeat(n);
  }

  static Stream<Arguments> leadBytes() {
    List<Arguments> cases = new ArrayList<>();
    // One byte at B4091. With ] at B8183: N=1 when the block is not extended or extended by two or three, 0 when by one.
    int[][] late = {{0xc0, 1}, {0xc1, 1}, {0xc2, 0}, {0xdf, 0}, {0xe0, 1}, {0xef, 1}, {0xf0, 1}, {0xf4, 1}, {0xf5, 1}, {0xf8, 1}, {0xff, 1}};
    for (int[] c : late) {
      cases.add(Arguments.argumentSet(String.format("%02X at B4091, ] at B8183, LF", c[0]), bytes("[\"", xs(4088), of(c[0]), xs(4090), "\"]\n"), c[1]));
    }
    // With ] at B8182: N=0 only when the block is not extended.
    int[][] early = {{0x80, 0}, {0xbf, 0}, {0xc0, 0}, {0xc1, 0}, {0xc2, 1}, {0xe0, 1}, {0xf0, 1}, {0xf4, 1}, {0xf5, 0}, {0xf8, 0}, {0xfe, 0}, {0xff, 0}};
    for (int[] c : early) {
      cases.add(Arguments.argumentSet(String.format("%02X at B4091, ] at B8182, LF", c[0]), bytes("[\"", xs(4088), of(c[0]), xs(4089), "\"]\n"), c[1]));
    }
    return cases.stream();
  }

  static Stream<Arguments> cuts() {
    return Stream.of(
        // E2 82 ends the block: one byte missing, so the block takes B4092 and ] at B8183 ends the next one.
        Arguments.argumentSet("E2 82 at B4090..4091, ] at B8183, LF", bytes("[\"", xs(4087), of(0xe2, 0x82), xs(4090), "\"]\n"), 0),
        // C3 82 82: more continuation bytes than C3 takes, so nothing is missing.
        Arguments.argumentSet("C3 82 82 at B4089..4091, ] at B8182, LF", bytes("[\"", xs(4086), of(0xc3, 0x82, 0x82), xs(4089), "\"]\n"), 0),
        // The completion takes the closing bytes, an LF among them, and the block still does not count.
        Arguments.argumentSet("E2 at B4091, then \" ] LF", bytes("[\"", xs(4088), of(0xe2), "\"]\n"), 0),
        Arguments.argumentSet("F0 at B4091, then \" ] LF", bytes("[\"", xs(4088), of(0xf0), "\"]\n"), 0),
        Arguments.argumentSet("F0 at B4091, then \" ] LF LF", bytes("[\"", xs(4088), of(0xf0), "\"]\n\n"), 0),
        // A second block of continuation bytes only takes nothing more: ] at B8184 is in a third block, with its LF.
        Arguments.argumentSet("4091 x 80 at B4092..8182, ] at B8184, LF", bytes("[\"", xs(4089), continuations(4091), "\"]\n"), 1),
        Arguments.argumentSet("E2 and 4090 x 80 at B4092..8182, ] at B8184, LF", bytes("[\"", xs(4089), of(0xe2), continuations(4090), "\"]\n"), 1),
        // A closing brace is the completing byte itself (jq-ref -c .a.b on these bytes): at B4091 it ends the first
        // block, which has no LF; at B4092 it is in the second block, with its LF.
        Arguments.argumentSet("} at B4091, LF", bytes("{\"a\":\"", xs(4083), "\"}\n"), 0),
        Arguments.argumentSet("} at B4092, LF", bytes("{\"a\":\"", xs(4084), "\"}\n"), 1),
        // A short last block after a longer one ending in LF counts only its own bytes.
        Arguments.argumentSet("[ 50 SP LF ]", bytes("[", " ".repeat(50), "\n]"), 1));
  }

  @ParameterizedTest(name = "{argumentSetName} -> {1}")
  @MethodSource({"leadBytes", "cuts"})
  void nCountsTheReadBlocksEndingInLf(byte[] input, int expected) {
    assertThat(ErrorLine.of(input)).as("N of %d bytes", input.length).isEqualTo(expected);
  }
}
