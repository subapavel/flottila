package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNumber;

/**
 * The number rules every operator shares (ADR-0012): a native read of a number's double, the one three-way compare,
 * jq's {@code jvp_number_cmp}, which Equality and Order use (ADR-0037, design approval O5), and jq's {@code
 * jv_number_negate} and {@code jv_number_abs}. A non-short view's token is read whole at each of them, so each charges
 * one tick per started KiB of it (ADR-0008 "Amendment (ticket #97, F-50)").
 */
public final class Numbers {
  private Numbers() {}

  /**
   * The double of {@code n} for a native operator: a non-short view (ADR-0012) computes it from its whole token, so it
   * first charges {@code state} one tick per started KiB of the token; a short view, a literal number's cached double
   * and a computed number charge nothing. The host accessor {@link JqNumber#doubleValue()} charges no run.
   */
  public static double doubleValue(RunState state, JqNumber n) {
    charge(state, n);
    return n.doubleValue();
  }

  /**
   * jq's {@code jvp_number_cmp} (ADR-0012 "Comparison"): -1, 0 or 1. Two literals compare as exact decimals: by long
   * when both fit one, by double when both are short, otherwise exactly ({@link CanonicalForm#compare}); any other
   * pair as doubles, with primitive {@code <} and {@code ==}, so {@code -0} equals {@code 0} and a NaN on either side
   * gives 1, as in jq. The exact path reads both tokens whole, so there it first charges {@code state} one tick per
   * started KiB of each token; the double path charges as {@link #doubleValue(RunState, JqNumber)} does, and the long
   * path nothing (ADR-0037 "Ticks", in Equality as in Order).
   */
  static int compare(RunState state, JqNumber a, JqNumber b) {
    if (a instanceof Literal x && b instanceof Literal y) {
      if (a.fitsLong() && b.fitsLong()) {
        return Long.compare(a.longValue(), b.longValue());
      }
      if (!x.isShort() || !y.isShort()) {
        int xoff = x.tokenOffset();
        int yoff = y.tokenOffset();
        state.tick(kib(x.tokenLength()) + kib(y.tokenLength()));
        return CanonicalForm.compare(x.tokenBytes(), xoff, x.tokenLength(), y.tokenBytes(), yoff, y.tokenLength());
      }
    }
    double x = doubleValue(state, a);
    double y = doubleValue(state, b);
    return x < y ? -1 : x == y ? 0 : 1;
  }

  /** One tick per started KiB of a non-short view's token, which a read of it scans or copies whole; else none. */
  private static void charge(RunState state, JqNumber n) {
    if (n instanceof NumberView v && !v.isShort()) {
      state.tick(kib(v.tokenLength()));
    }
  }

  /** The KiB that {@code bytes} bytes start: 0 for none. */
  private static int kib(int bytes) {
    return (bytes + 1023) >>> 10;
  }

  /**
   * jq's {@code jv_number_negate}: a literal's negation is a literal (ADR-0012, {@link LiteralNumber#negated}), a
   * computed number's is its double negated. It charges nothing; {@code -x} calls {@link #negate(RunState, JqNumber)}.
   */
  public static JqNumber negate(JqNumber n) {
    return switch (n) {
      case DoubleNumber d -> new DoubleNumber(-d.doubleValue());
      case Literal l -> l.negate();
    };
  }

  /**
   * {@link #negate(JqNumber)} for {@code -x}: it copies a view's token, so it first charges {@code state} as {@link
   * #doubleValue(RunState, JqNumber)} does.
   */
  public static JqNumber negate(RunState state, JqNumber n) {
    charge(state, n);
    return negate(n);
  }

  /**
   * jq's {@code jv_number_abs}, decNumber's {@code decNumberAbs} for a literal: a literal whose token has a sign is
   * negated ({@link #negate(JqNumber)}), so {@code -0} gives {@code 0} and {@code -1.10} gives {@code 1.10}, and any
   * other is itself; a computed number's is its double's absolute value, NaN staying NaN (jq-ref 1.8.2, recorded:
   * natives/length-kinds, natives/length-built). It first charges {@code state} as {@link #doubleValue(RunState,
   * JqNumber)} does, whether or not it copies the token.
   */
  public static JqNumber abs(RunState state, JqNumber n) {
    charge(state, n);
    return switch (n) {
      case DoubleNumber d -> new DoubleNumber(Math.abs(d.doubleValue()));
      case Literal l -> {
        // The offset first: it checks a view's document (Literal).
        int off = l.tokenOffset();
        yield l.tokenBytes()[off] == '-' ? l.negate() : n;
      }
    };
  }
}
