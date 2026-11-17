package io.github.subapavel.jqj.cli;

/**
 * The input stream (GLOSSARY) of one run, as spec 1 reads it: all of stdin, or all of the one file argument, holding
 * no document or one (#22, "Command line"; several documents and files are spec 5's).
 */
final class Input {

  /** The UTF-8 BOM, which jq strips at the start of the input stream (ADR-0015). */
  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private Input() {}

  /**
   * Whether {@code stream} holds no document; jq then runs nothing and exits 0. That is nothing or only jq's whitespace,
   * after an optional leading UTF-8 BOM (EF BB BF), or a partial BOM cut by the end of the stream: EF or EF BB alone
   * (ADR-0015; jq-ref 1.8.2, diff/string.rec {@code bom-*}). A partial BOM followed by anything else holds a document,
   * whose read fails.
   */
  static boolean holdsNoDocument(byte[] stream) {
    // The leading bytes of the stream that are the BOM's.
    int bom = 0;
    while (bom < BOM.length && bom < stream.length && stream[bom] == BOM[bom]) {
      bom++;
    }
    if (bom == stream.length) {
      // Nothing, the whole BOM, or a partial one cut by the end of the stream.
      return true;
    }
    // Only a whole BOM is skipped: after a partial one the stream starts with EF, which is no whitespace.
    for (int i = bom == BOM.length ? bom : 0; i < stream.length; i++) {
      if (!isSpace(stream[i])) {
        return false;
      }
    }
    return true;
  }

  /** jq's whitespace between documents and tokens (ADR-0015): SP, TAB, CR and LF. */
  static boolean isSpace(byte b) {
    return b == ' ' || b == '\t' || b == '\r' || b == '\n';
  }
}
