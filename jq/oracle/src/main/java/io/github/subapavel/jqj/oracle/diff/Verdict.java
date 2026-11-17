package io.github.subapavel.jqj.oracle.diff;

/**
 * A case's verdict in the oracle report (ADR-0020).
 *
 * @param detail one line, no tab: why, for the report's detail column
 * @param diff the readable comparison for a failing test, empty when the case passes
 */
public record Verdict(Kind kind, String detail, String diff) {

  /** The mark every Stub message carries (ADR-0024); both runners score a run that ends with it FAIL. */
  public static final String STUB = "jqj stub: ";

  public enum Kind {
    PASS,
    FAIL,
    STALE,
    CRASH
  }
}
