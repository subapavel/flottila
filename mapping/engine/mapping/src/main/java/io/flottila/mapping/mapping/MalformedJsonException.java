package io.flottila.mapping.mapping;

import java.io.Serial;

/**
 * The bytes are no mapping-model document at all: not well-formed UTF-8, a byte order mark, not RFC
 * 8259 JSON, or a key twice in one object. It is a transport error, not a diagnostic (ADR-0011,
 * ADR-0015).
 */
public final class MalformedJsonException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param message what is wrong and where
   */
  public MalformedJsonException(String message) {
    super(message);
  }
}
