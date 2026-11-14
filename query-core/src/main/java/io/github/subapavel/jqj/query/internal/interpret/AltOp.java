package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Truth;

/**
 * {@code left // right} (ADR-0037): the left side's results for which the truth holds, as they come, and the right
 * side's only when there was none. A false on the left, a stop or a break, returns at once, without the right
 * (ADR-0039). It catches nothing (ADR-0008) and charges no step of its own.
 */
public final class AltOp extends Op {
  private final Op left;
  private final Op right;
  private final Truth truth;

  public AltOp(Op left, Op right, Truth truth) {
    this.left = left;
    this.right = right;
    this.truth = truth;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Truthy found = new Truthy(truth, out);
    if (!left.run(state, slots, in, found)) {
      return false;
    }
    return found.any || right.run(state, slots, in, out);
  }

  /** The left side's sink: it passes on the results the truth holds for and drops the others. */
  private static final class Truthy implements Sink {
    private final Truth truth;
    private final Sink out;

    /** Whether a result passed. */
    private boolean any;

    Truthy(Truth truth, Sink out) {
      this.truth = truth;
      this.out = out;
    }

    @Override
    public boolean emit(Object v) {
      if (!truth.test(v)) {
        return true;
      }
      any = true;
      return out.emit(v);
    }
  }
}
