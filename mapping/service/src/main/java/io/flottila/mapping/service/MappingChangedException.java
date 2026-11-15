package io.flottila.mapping.service;

import java.io.Serial;

/**
 * A save whose {@code If-Match} differs from the stored file's canonical ETag: the file changed
 * since the editor read it (ADR-0027). Nothing is written.
 */
final class MappingChangedException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  MappingChangedException(String detail) {
    super(detail);
  }
}
