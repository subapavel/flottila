package io.flottila.mapping.structure.importer;

import java.io.Serial;

/**
 * A reference a {@link ReferenceResolver} cannot turn into a file (ADR-0014 "Reference resolver
 * shape"). It carries a reason and the reference only, never the host's own text: the engine
 * reports it as a load error at line 1, column 1 of the reference, with a message of its own.
 */
public final class UnresolvedReferenceException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /** Why a reference is not resolved. */
  public enum Reason {
    /** The host cannot read the file. */
    UNREADABLE,
    /** A package reference ({@code name@version:/path}), which this host does not read. */
    PACKAGE_REFERENCE_UNSUPPORTED
  }

  private final Reason reason;

  /**
   * @param reason why the reference is not resolved
   * @param reference the reference as the schema bundle spells it
   */
  public UnresolvedReferenceException(Reason reason, String reference) {
    super(message(reason, reference));
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  /** The engine's message for a reference that is not resolved for {@code reason}. */
  static String message(Reason reason, String reference) {
    return switch (reason) {
      case UNREADABLE -> "cannot read the schema file \"" + reference + "\"";
      case PACKAGE_REFERENCE_UNSUPPORTED ->
          "package references need a host resolver: \"" + reference + "\"";
    };
  }
}
