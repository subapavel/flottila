package dev.rail8.jq.internal.value;

import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqNumber;

/**
 * The number rules every operator shares (ADR-0012): a native read of a number's double, the one three-way compare,
 * jq's {@code jvp_number_cmp}, which Equality and Order use (ADR-0037, design approval O5), and jq's {@code
 * jv_number_negate} and {@code jv_number_abs}. Each charges one tick per started KiB of a token it reads whole (ADR-0008
 * "Amendment (ticket #97, F-50)"): a read, a non-short view's; a copy by {@code -x} or {@code length}, any non-short
 * literal's, a literal the run built included.
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

  /**
   * One tick per started KiB of a non-short view's token, which a read of its double scans whole; else none: a short
   * view's double orders as its decimal does, and a literal number's double is cached.
   */
  private static void charge(RunState state, JqNumber n) {
    if (n instanceof NumberView v && !v.isShort()) {
      state.tick(kib(v.tokenLength()));
    }
  }

  /**
   * One tick per started KiB of a non-short literal's token, view or literal number, which {@code -x} and {@code
   * length} read whole to copy it; else none: a short literal and a computed number.
   */
  private static void chargeCopy(RunState state, JqNumber n) {
    if (n instanceof Literal l && !l.isShort()) {
      state.tick(kib(l.tokenLength()));
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
   * {@link #negate(JqNumber)} for {@code -x}: it copies a literal's token, so it first charges {@code state} one tick
   * per started KiB of a non-short literal's token, a literal the run built included.
   */
  public static JqNumber negate(RunState state, JqNumber n) {
    chargeCopy(state, n);
    return negate(n);
  }

  /**
   * jq's {@code jv_number_abs}, decNumber's {@code decNumberAbs} for a literal: a literal whose token has a sign is
   * negated ({@link #negate(JqNumber)}), so {@code -0} gives {@code 0} and {@code -1.10} gives {@code 1.10}, and any
   * other is itself; a computed number's is its double's absolute value, NaN staying NaN (jq-ref 1.8.2, recorded:
   * natives/length-kinds, natives/length-built). It first charges {@code state} as {@link #negate(RunState, JqNumber)}
   * does, whether or not it copies the token.
   */
  public static JqNumber abs(RunState state, JqNumber n) {
    chargeCopy(state, n);
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
