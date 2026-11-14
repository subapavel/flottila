package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** {@code left | right}: each result of the left side runs the right side, and a stop on the right comes back. */
public final class PipeOp extends Op {
  private final Op left;
  private final Op right;

  public PipeOp(Op left, Op right) {
    this.left = left;
    this.right = right;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return left.run(state, slots, in, v -> right.run(state, slots, v, out));
  }
}
