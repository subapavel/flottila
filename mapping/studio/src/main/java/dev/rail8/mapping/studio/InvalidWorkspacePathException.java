package dev.rail8.mapping.studio;

import java.io.Serial;

/**
 * A bundle reference the call names as no workspace path: a 400 {@code invalid-workspace-path}. It
 * is unchecked so that it passes through the engine unchanged, at once.
 */
final class InvalidWorkspacePathException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  InvalidWorkspacePathException(String reference) {
    super("not a workspace path: \"" + reference + "\"");
  }
}
