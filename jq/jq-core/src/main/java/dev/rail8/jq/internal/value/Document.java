package dev.rail8.jq.internal.value;

import dev.rail8.jq.JqTermination;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;

/**
 * One read's document: the UTF-8 bytes and the tape the reader wrote, the root at tape index 0. A view holds the
 * document and a tape index. Dropping the document makes every view of it stale.
 */
public final class Document {
  /** jq's exit code for a failed evaluation, which a stale view carries. */
  private static final int STALE_EXIT_CODE = 5;

  /** The bytes the tape's offsets index; null once dropped. */
  byte[] bytes;

  /** Null once dropped. */
  long[] tape;

  public Document(byte[] bytes, long[] tape) {
    this.bytes = bytes;
    this.tape = tape;
  }

  /** The root value. */
  public JqValue root() {
    return valueAt(live(), 0);
  }

  /** Makes every view of this document stale; dropping it again does nothing. */
  public void drop() {
    bytes = null;
    tape = null;
  }

  /** The bytes the tape's offsets index; call {@link #live()} first, which tells a dropped document. */
  public byte[] bytes() {
    return bytes;
  }

  /**
   * The tape, checked once per accessor call.
   *
   * @throws JqTermination with cause {@code STALE_VIEW} once the document is dropped
   */
  public long[] live() {
    long[] live = tape;
    if (live == null) {
      throw new JqTermination(JqTermination.Cause.STALE_VIEW, STALE_EXIT_CODE, JqNull.NULL, null);
    }
    return live;
  }

  /** The value at tape index {@code i}: null and the booleans as their enums, the rest as views. */
  JqValue valueAt(long[] live, int i) {
    return switch (Tape.tag(live[i])) {
      case Tape.NULL -> JqNull.NULL;
      case Tape.FALSE -> JqBool.FALSE;
      case Tape.TRUE -> JqBool.TRUE;
      case Tape.NUMBER -> new NumberView(this, i);
      case Tape.STRING, Tape.STRING_REENCODE -> new StringView(this, i);
      case Tape.ARRAY -> new ArrayView(this, i);
      // Tape.OBJECT: the reader writes no other tag.
      default -> new ObjectView(this, i);
    };
  }
}
