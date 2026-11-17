package dev.rail8.jq.oracle.diff;

/**
 * A case's verdict in the oracle report.
 *
 * @param detail one line, no tab: why, for the report's detail column
 * @param diff the readable comparison for a failing test, empty when the case passes
 */
public record Verdict(Kind kind, String detail, String diff) {

  /** The mark every Stub message carries; both runners score a run that ends with it FAIL. */
  public static final String STUB = "rail8-jq stub: ";

  public enum Kind {
    PASS,
    FAIL,
    STALE,
    CRASH
  }
}
