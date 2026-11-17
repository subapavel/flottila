package io.github.subapavel.jqj.internal;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqSyntaxException;

/**
 * GLOSSARY "Stub", ADR-0024: a body that is not built yet ends with a {@link JqCompileException} whose
 * message starts with {@link #PREFIX}, and a malformed document with a {@link JqSyntaxException} whose message
 * does. The oracle runners score a case FAIL by that prefix.
 */
public final class Stub {
  /** The mark every Stub message starts with. */
  public static final String PREFIX = "jqj stub: ";

  private Stub() {}

  /** The exception a Stub throws; {@code what} names the construct, e.g. {@code JqEngine.compile}. */
  public static JqCompileException unbuilt(String what) {
    return new JqCompileException(PREFIX + what + " is not built yet", 1, 1);
  }

  /**
   * The malformed-document Stub (ADR-0024): jq's text comes by replay in spec 5; until then the mark with the line
   * and column of the detecting byte.
   */
  public static JqSyntaxException malformed(long offset, int line, int column) {
    return new JqSyntaxException(PREFIX + "malformed document at line " + line + ", column " + column, offset, line, column);
  }
}
