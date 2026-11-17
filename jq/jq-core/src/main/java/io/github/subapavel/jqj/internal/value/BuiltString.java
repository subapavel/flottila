package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;

/**
 * A string built at run time (#34 design section 7), such as jq's error texts: UTF-8 bytes {@code [0, length)} of
 * {@code bytes}. Public only so the kind can permit it.
 */
public final class BuiltString implements JqString {
  private final byte[] bytes;
  private final int length;

  /**
   * The ownership mark of ADR-0022: every built class carries it from the ticket that creates the class, and it stays
   * null until spec 3 (spec-1 grilling round 1, item 9).
   */
  private final Object owner;

  private BuiltString(byte[] bytes, int length) {
    this.bytes = bytes;
    this.length = length;
    this.owner = null;
  }

  /** The string of {@code text}'s UTF-8 bytes. */
  public static BuiltString of(String text) {
    return of(text.getBytes(UTF_8));
  }

  /** The string of {@code utf8}, well-formed UTF-8 that it keeps: the caller never writes the array again. */
  public static BuiltString of(byte[] utf8) {
    return new BuiltString(utf8, utf8.length);
  }

  /** jq's compact text (ADR-0015, jq's re-escape). */
  @Override
  public String toJson() {
    return Printer.json(this);
  }

  /** The array holding the UTF-8 bytes, {@code [0, byteLength())} of it; never written. */
  public byte[] bytes() {
    return bytes;
  }

  /** The number of UTF-8 bytes. */
  public int byteLength() {
    return length;
  }

  @Override
  public String value() {
    return new String(bytes, 0, length, UTF_8);
  }

  /** A read-only slice of the bytes, without a copy. */
  @Override
  public MemorySegment utf8() {
    return MemorySegment.ofArray(bytes).asSlice(0, length).asReadOnly();
  }
}
