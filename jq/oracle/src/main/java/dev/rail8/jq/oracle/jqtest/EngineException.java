package dev.rail8.jq.oracle.jqtest;

import dev.rail8.jq.oracle.diff.Verdict;
import java.util.List;

/** An exception of the engine's, as {@link Engine} hands it to the runner: its kind, message and compile error blocks. */
public final class EngineException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Which of the engine's four exceptions it was. */
  public enum Kind {
    COMPILE,
    SYNTAX,
    FILTER,
    TERMINATION
  }

  private final Kind kind;

  // List.copyOf's lists are serializable; the field's type does not say so.
  @SuppressWarnings("serial")
  private final List<String> blocks;

  /** @param blocks the compile error blocks, in order; empty for the other kinds */
  public EngineException(Kind kind, String message, List<String> blocks) {
    super(message, null, false, false);
    this.kind = kind;
    this.blocks = List.copyOf(blocks);
  }

  public Kind kind() {
    return kind;
  }

  public List<String> blocks() {
    return blocks;
  }

  /** Whether the engine ended with a Stub: the message carries {@value Verdict#STUB}. */
  boolean stub() {
    return String.valueOf(getMessage()).contains(Verdict.STUB);
  }
}
