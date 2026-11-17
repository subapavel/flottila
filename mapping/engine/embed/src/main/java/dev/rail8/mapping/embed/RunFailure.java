package dev.rail8.mapping.embed;

import java.io.Serial;
import java.util.Optional;

/** A run of a {@link Mapping} that gave no output, and why. */
public final class RunFailure extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Why a run gave no output. Reasons may be added: a host maps them with a {@code switch} that has
   * no {@code default}, so that a new one is a compile error there.
   */
  public enum Reason {
    /**
     * The input is not well-formed XML without a DOCTYPE, or JSON, as the source structure says.
     */
    MALFORMED_INPUT,
    /** The run met a runtime error in its data, such as {@code po:MINOCCURS}. */
    DATA_ERROR,
    /** The output passed the run's limit. */
    OUTPUT_TOO_LARGE
  }

  private final Reason reason;

  /** The data error's code, or {@code null}. */
  private final String code;

  /** The data error's authoring id, or {@code null}. */
  private final String authoringId;

  RunFailure(Reason reason, String message, String code, String authoringId, Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.code = code;
    this.authoringId = authoringId;
  }

  public Reason reason() {
    return reason;
  }

  /** For {@link Reason#DATA_ERROR}, the error's QName, such as {@code err:FORG0001}. */
  public Optional<String> code() {
    return Optional.ofNullable(code);
  }

  /**
   * For {@link Reason#DATA_ERROR}, where the error was raised: the target field mapping or the
   * function box.
   */
  public Optional<String> authoringId() {
    return Optional.ofNullable(authoringId);
  }
}
