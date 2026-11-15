package io.flottila.mapping.service;

import java.io.Serial;

/** An id, base or bundle reference that is no workspace path (ADR-0024): never a read. */
public final class WorkspacePathException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param path the refused text, as the request spelled it
   */
  public WorkspacePathException(String path) {
    super("not a workspace path: \"" + path + "\"");
  }
}
