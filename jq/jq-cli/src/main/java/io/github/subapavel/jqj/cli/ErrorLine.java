package io.github.subapavel.jqj.cli;

/**
 * The N of jq's {@code (at <file>:N)} for an input of one document, from its bytes alone (#34 design section 9; it
 * goes away with #38's input reader). N counts the read blocks that end in LF, up to the block that holds the
 * completing byte (GLOSSARY "Read block", "Completing byte", F-25).
 */
final class ErrorLine {
  /** The most bytes jq's read takes before an LF: a 4096-byte buffer read with {@code max_gets_len = 4092}. */
  private static final int BLOCK = 4091;

  private ErrorLine() {}

  /** N for {@code input}, a document with optional whitespace around it. */
  static int of(byte[] input) {
    int completing = completing(input);
    int n = 0;
    int start = 0;
    for (;;) {
      int limit = Math.min(start + BLOCK, input.length);
      int end = lf(input, start, limit);
      if (end >= 0) {
        // A block cut at its LF counts.
        end++;
        n++;
      } else {
        // A block cut at 4091 bytes, or at the end of the input, counts not; it takes the rest of a character cut there.
        end = Math.min(limit + missing(input, start, limit), input.length);
      }
      if (completing < end || end == input.length) {
        return n;
      }
      start = end;
    }
  }

  /**
   * The index of the completing byte: the last byte that is not SP, TAB, CR or LF when it is {@code ]}, {@code }} or
   * {@code "}, otherwise the byte after it, which is the length of the input at its end.
   */
  private static int completing(byte[] input) {
    int last = input.length - 1;
    while (Input.isSpace(input[last])) {
      last--;
    }
    byte b = input[last];
    return b == ']' || b == '}' || b == '"' ? last : last + 1;
  }

  /** The index of the first LF in {@code input[from, to)}, or -1. */
  private static int lf(byte[] input, int from, int to) {
    for (int i = from; i < to; i++) {
      if (input[i] == '\n') {
        return i;
      }
    }
    return -1;
  }

  /**
   * The bytes of a UTF-8 character that {@code input[start, end)} leaves out at its end, as jq's
   * {@code jvp_utf8_backtrack} counts them: back over the continuation bytes to a lead byte, then its length less the
   * bytes seen. A byte that leads nothing (a block of continuation bytes only ends at one), or more continuation bytes
   * than the lead takes, leaves out none.
   */
  private static int missing(byte[] input, int start, int end) {
    int p = end - 1;
    while (p > start && (input[p] & 0xC0) == 0x80) {
      p--;
    }
    return Math.max(length(input[p] & 0xFF) - (end - p), 0);
  }

  /**
   * The length of the character a byte starts, 0 for a byte that starts none: a continuation byte, C0, C1 and F5 to
   * FF (jq-ref 1.8.2, observed 2026-11-11 with each byte at a cut: C0, C1 and F5 to FF take no byte after them, C2 to
   * DF one, E0 to EF two, F0 to F4 three).
   */
  private static int length(int lead) {
    if (lead < 0x80) {
      return 1;
    }
    if (lead < 0xC2) {
      return 0;
    }
    if (lead < 0xE0) {
      return 2;
    }
    if (lead < 0xF0) {
      return 3;
    }
    return lead < 0xF5 ? 4 : 0;
  }
}
