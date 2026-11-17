package io.github.subapavel.jqj;

import java.util.List;

/**
 * A filter that does not compile (ADR-0016, ADR-0031). Its message is jq's command-line text: every error block (the
 * message, its position, the source line and carets), joined by LF, then {@code jq: N compile error} or
 * {@code jq: N compile errors}. Its line and column are the first block's, 1-based and counted in bytes of the filter's
 * UTF-8: the line is the LFs before the error plus 1, the column the bytes since that line's start plus 1. A Stub ends
 * with one too, whose message is its one block and starts with {@code jqj stub: } (ADR-0024).
 */
public final class JqCompileException extends JqException {
  private static final long serialVersionUID = 1L;

  private final int line;
  private final int column;

  /** The error blocks, a copy of the caller's. */
  private final String[] blocks;

  /** A compile error whose message is its one block, with no count line: a Stub's. */
  public JqCompileException(String message, int line, int column) {
    super(message);
    this.line = line;
    this.column = column;
    this.blocks = new String[] {message};
  }

  /**
   * jq's compile errors: {@code blocks} joined by LF, then jq's count line; {@code line} and {@code column} are the
   * first block's (ADR-0031). The blocks are copied, so a later change to {@code blocks} changes nothing here.
   */
  public JqCompileException(List<String> blocks, int line, int column) {
    this(blocks.toArray(String[]::new), line, column);
  }

  private JqCompileException(String[] blocks, int line, int column) {
    super(String.join("\n", blocks) + "\njq: " + blocks.length + (blocks.length > 1 ? " compile errors" : " compile error"));
    this.line = line;
    this.column = column;
    this.blocks = blocks;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  /** The error blocks of the message, in order, unmodifiable. */
  public List<String> blocks() {
    return List.of(blocks);
  }
}
