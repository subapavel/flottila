package dev.rail8.jq.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.internal.json.Printer;
import dev.rail8.jq.value.JqString;
import java.lang.foreign.MemorySegment;

/**
 * A string built at run time, such as jq's error texts: UTF-8 bytes {@code [0, length)} of {@code bytes}. Public only
 * so the kind can permit it.
 */
public final class BuiltString implements JqString {
  private final byte[] bytes;
  private final int length;

  /**
   * The ownership mark for updates in place: the activation that made the value and may write it without a copy. Every
   * built class carries it; nothing sets it, so it is always null and no built value is written in place.
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

  /** jq's compact text, re-escaped as jq does. */
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
