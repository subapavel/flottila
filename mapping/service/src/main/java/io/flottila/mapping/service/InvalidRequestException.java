package io.flottila.mapping.service;

import java.io.Serial;

/**
 * A request body that is not of its endpoint's shape where Jackson cannot tell: no body at all, or
 * a root selector of none of the model schema's shapes (ADR-0024).
 */
final class InvalidRequestException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  InvalidRequestException(String detail) {
    super(detail);
  }

  /**
   * The bound request: an empty body and the body {@code null} both bind to {@code null}, which is
   * no request of any endpoint.
   */
  static <T> T present(T request) throws InvalidRequestException {
    if (request == null) {
      throw new InvalidRequestException("the request body is empty or null");
    }
    return request;
  }
}
