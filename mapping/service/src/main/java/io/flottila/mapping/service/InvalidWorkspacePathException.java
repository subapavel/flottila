package io.flottila.mapping.service;

import java.io.Serial;

/**
 * A bundle reference the workspace's reference resolver refuses as no workspace path (ADR-0014
 * "Reference resolver shape", ADR-0024): a pattern refusal or a workspace escape. It is named after
 * its problem, 400 {@code invalid-workspace-path}, which it answers as its cause does, and it is
 * unchecked so that it passes through the engine unchanged.
 */
public final class InvalidWorkspacePathException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param cause the refusal, which names the reference as the bundle spells it
   */
  public InvalidWorkspacePathException(WorkspacePathException cause) {
    super(cause.getMessage(), cause);
  }
}
