package io.flottila.mapping.runtime;

import java.io.Serial;

/**
 * An input message that is not well-formed in its mapping's input format (ADR-0008, #98): no
 * well-formed XML document, or one with a DOCTYPE, which is refused because no DTD is ever read; or
 * no JSON text by RFC 8259, one that is no UTF-8, or one whose object has a key twice. The parser's
 * message names the position.
 */
public final class MalformedInputException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  MalformedInputException(String message, Throwable cause) {
    super(message, cause);
  }
}
