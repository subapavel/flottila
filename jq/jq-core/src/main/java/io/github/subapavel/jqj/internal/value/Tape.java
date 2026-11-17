package io.github.subapavel.jqj.internal.value;

/**
 * The tape entry of ADR-0002: one {@code long} per JSON value, object keys included, holding a 4-bit tag, a 28-bit
 * span and a 32-bit offset into the document's bytes. A scalar's span is its byte length (a string's includes its
 * quotes) and its offset its first byte; a container's span is the number of entries inside it and its offset its
 * closing bracket. Tags 0 (invalid), 7 (skipped value) and 10 to 15 are reserved; no reader writes them yet.
 */
public final class Tape {
  public static final int NULL = 1;
  public static final int FALSE = 2;
  public static final int TRUE = 3;
  public static final int NUMBER = 4;

  /** A string whose bytes are its value and its compact output. */
  public static final int STRING = 5;

  /** A string that must be re-encoded: one with an escape, DEL or bytes that are not well-formed UTF-8 (ADR-0015). */
  public static final int STRING_REENCODE = 6;

  public static final int ARRAY = 8;
  public static final int OBJECT = 9;

  /** ADR-0012: a number's span flag for an integer token, not {@code -0}, inside the long range. */
  public static final int FITS_LONG = 1 << 27;

  /** ADR-0012: a number's span flag for a token whose bytes are what jq prints for it, so the writer copies them. */
  public static final int CANONICAL = 1 << 26;

  /** A number's span below its four flags: the token's byte length. */
  public static final int NUMBER_LENGTH = 0x00FF_FFFF;

  private Tape() {}

  public static long entry(int tag, int span, int off) {
    return (long) tag << 60 | (long) span << 32 | (off & 0xFFFF_FFFFL);
  }

  public static int tag(long entry) {
    return (int) (entry >>> 60);
  }

  public static int span(long entry) {
    return (int) (entry >>> 32) & 0x0FFF_FFFF;
  }

  public static int off(long entry) {
    return (int) entry;
  }

  /**
   * The index of the entry after the value at {@code i} and everything inside it. A tag from 8 sets the sign bit
   * (ADR-0002), so {@code entry >> 63} is all ones for a container, whose span is skipped, and zero for a scalar.
   */
  public static int next(long[] tape, int i) {
    long entry = tape[i];
    return i + 1 + ((int) (entry >> 63) & span(entry));
  }

  /** The index after the last entry inside the container at {@code i}. */
  public static int end(long[] tape, int i) {
    return i + 1 + span(tape[i]);
  }
}
