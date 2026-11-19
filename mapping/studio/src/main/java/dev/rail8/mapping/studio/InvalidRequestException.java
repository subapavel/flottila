package dev.rail8.mapping.studio;

import java.io.Serial;

/**
 * A request that is not of its operation's shape where Jackson cannot tell: no body at all, or a
 * root selector of none of the model schema's shapes; or a call that is not of the studio's shape.
 */
final class InvalidRequestException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  InvalidRequestException(String detail) {
    super(detail);
  }
}
