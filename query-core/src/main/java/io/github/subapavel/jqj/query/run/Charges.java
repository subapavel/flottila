package io.github.subapavel.jqj.query.run;

/**
 * The bytes a builder charges with {@link RunState#chargeBuilt} before it allocates (ADR-0035, ADR-0017). An element
 * and an entry are the rates ADR-0017 sets; a container and a string are fixed charges per built value, measured with
 * JMH {@code -prof gc} as the bytes one empty value allocates (bench/RESULTS.md, #64). A number box is never charged.
 */
public final class Charges {
  private Charges() {}

  /** One element of a built array: a reference slot. */
  public static final long ELEMENT = 8;

  /** One entry of a built object: a key and a value. */
  public static final long ENTRY = 16;

  /**
   * Each built array or object, whatever it holds: the largest of an empty array (40 B), an object of a shaped site
   * (48 B) and a growable object (64 B), measured 2026-11-13 (bench/RESULTS.md).
   */
  public static final long CONTAINER = 64;

  /** Each built string, on top of its UTF-8 length: an empty string, 40 B, measured 2026-11-13 (bench/RESULTS.md). */
  public static final long STRING = 40;
}
