package io.github.subapavel.jqj.bench;

/**
 * The recursion, {@code limit}, {@code ..} and cap diagnostics of spec 2b (#119; ADR-0019 and its #119 amendment,
 * ADR-0006, ADR-0011).
 */
public class Diagnostics {

  /** Spike A's prefix of every recursion shape: the input as {@code $a}, its length as {@code $L}. */
  static final String PREFIX = ". as $a | ($a|length) as $L | ";

  /** T, Spike A's self tail call: state {@code [i, acc]}, the call the last thing the function does. */
  static final String T =
      PREFIX + "def s: if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | s end; [0, 0] | s";

  /** The run E and Eu share: the sum of {@code $a} over the results of {@code r}. */
  private static final String SUM_OF_R = "reduce (0 | r(if . < $L - 1 then . + 1 else empty end)) as $i (0; . + $a[$i])";

  /** E, Spike A's self call once per result of {@code f}, jq's own {@code recurse/1} shape. */
  static final String E = PREFIX + "def r(f): def rr: ., (f | rr); rr; " + SUM_OF_R;

  /**
   * Eu, E with permuted parameters: no parameter is invariant, so the optimised compile makes no copy (ADR-0006) and
   * the call stays RECURSIVE.
   */
  static final String EU = PREFIX + "def r(f): def rr(g; h): ., (g | rr(h; g)); rr(f; f); " + SUM_OF_R;

  /** M, Spike A's two functions calling each other, both in tail position. */
  static final String M =
      PREFIX
          + "def ev: def od: if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | ev end;"
          + " if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | od end; [0, 0] | ev";

  /** N, Spike A's non-tail recursion: the addition waits for the recursive result. */
  static final String N = PREFIX + "def s: if . == $L then 0 else $a[.] + (. + 1 | s) end; 0 | s";

  /** {@code limit} over an iteration. */
  static final String LIMIT_ITER = "[limit(length - 1; .[])] | length";

  /** {@code limit} over a recursion. */
  static final String LIMIT_RECURSE = "last(limit(length; 0 | recurse(. + 1)))";

  /** {@code ..}, on orders and on {@code deep}. */
  static final String DOTDOT = "[..] | length";

  /**
   * The largest k whose {@link #inlineChain} {@code explain()} shows fully inlined, with no function, measured on
   * 9052c29: k + 1 lifts every {@code f<i>}.
   */
  static final int INLINE_K = 15;

  /**
   * The largest m whose {@link #specialiseTree} {@code explain()} shows with {@code refused=0}, measured on 9052c29:
   * each {@code until} copy holds 10 nodes, so the m closures of the tree and the long {@code until} hold 16 380 of the
   * cap's 16 384, and at m + 1 the long {@code until}, the last key, is refused and calls the original. jq-ref rejects
   * the tree from 2047 closures (rc 3), so m + 1 stays at most 2046.
   */
  static final int SPECIALISE_M = 1_637;

  /** The element count of the 1e6 cells. */
  static final int INTS_1E6 = 1_000_000;

  /** The levels of {@code dotdot-deep.9999}, one below the nesting depth 10 000 of {@code Limits.embedded()}. */
  static final int DEEP_9999 = 9_999;

  /** The doubling chain of {@code k} levels: {@code def f1: . + 1; def f2: f1 | f1; …; 0 | fk}. */
  static String inlineChain(int k) {
    StringBuilder text = new StringBuilder("def f1: . + 1; ");
    for (int i = 2; i <= k; i++) {
      text.append("def f").append(i).append(": f").append(i - 1).append(" | f").append(i - 1).append("; ");
    }
    return text.append("0 | f").append(k).toString();
  }

  /**
   * {@code [P_m] | length + (0 | until(. >= 100000; . + 1))}, where {@code P_m} is a balanced comma tree of the
   * {@code m} distinct closures {@code (0 | until(. >= 1; . + i))}, {@code i} from 1 to {@code m}: each is a key of its
   * own for the specialiser, and the long {@code until} after them is the last key, the one refused past the cap.
   */
  static String specialiseTree(int m) {
    StringBuilder text = new StringBuilder("[");
    tree(text, 1, m);
    return text.append("] | length + (0 | until(. >= 100000; . + 1))").toString();
  }

  /** The balanced comma tree of the closures {@code from} to {@code to}, each in parentheses. */
  private static void tree(StringBuilder text, int from, int to) {
    if (from == to) {
      text.append("(0 | until(. >= 1; . + ").append(from).append("))");
      return;
    }
    int middle = (from + to) >>> 1;
    text.append('(');
    tree(text, from, middle);
    text.append(", ");
    tree(text, middle + 1, to);
    text.append(')');
  }
}
