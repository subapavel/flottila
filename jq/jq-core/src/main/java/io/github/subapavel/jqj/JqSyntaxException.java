package io.github.subapavel.jqj;

/**
 * A malformed document, thrown by reading (ADR-0016): jq's text, the byte offset and the 1-based line and
 * column. Inside a filter a syntax error is a {@link JqFilterException} instead.
 */
public final class JqSyntaxException extends JqException {
  private static final long serialVersionUID = 1L;

  private final long offset;
  private final int line;
  private final int column;

  public JqSyntaxException(String message, long offset, int line, int column) {
    super(message);
    this.offset = offset;
    this.line = line;
    this.column = column;
  }

  /** The byte offset in the document; a long, as a document may be longer than an int counts. */
  public long offset() {
    return offset;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }
}
