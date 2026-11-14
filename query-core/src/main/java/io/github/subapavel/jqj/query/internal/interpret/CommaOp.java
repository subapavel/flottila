package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code left, right} (ADR-0034): the left side's results, then one step for entering the right side (F-32), whatever
 * the left gave, then the right side's results. A stop on the left returns at once, without the step.
 */
public final class CommaOp extends Op {
  private final Op left;
  private final Op right;

  public CommaOp(Op left, Op right) {
    this.left = left;
    this.right = right;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    if (!left.run(state, slots, in, out)) {
      return false;
    }
    state.tick();
    return right.run(state, slots, in, out);
  }
}
