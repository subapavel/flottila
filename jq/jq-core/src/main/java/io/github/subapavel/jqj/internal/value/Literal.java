package io.github.subapavel.jqj.internal.value;

/**
 * A number that keeps jq's decimal meaning (ADR-0012, GLOSSARY "Literal number"): a document's or a filter's, as
 * opposed to a {@link DoubleNumber}. Two literals compare exactly ({@link Numbers#compare}), and negating one gives a
 * literal ({@code jv_number_negate}).
 */
sealed interface Literal permits NumberView, LiteralNumber {
  /** The array that holds the strict token; call {@link #tokenOffset()} first, which checks a view's document. */
  byte[] tokenBytes();

  /** Where the token starts in {@link #tokenBytes()}. */
  int tokenOffset();

  /** The token's byte length. */
  int tokenLength();

  /**
   * Whether the token is canonical, has no exponent and is at most 15 bytes long (ADR-0012 "Flags"), so its double
   * orders it as its decimal does.
   */
  boolean isShort();

  /** jq's {@code jv_number_negate} of the literal: a literal (ADR-0012), see {@link LiteralNumber#negated}. */
  default LiteralNumber negate() {
    int off = tokenOffset();
    return LiteralNumber.negated(tokenBytes(), off, tokenLength());
  }
}
