package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call of a {@link Many} as a loop over its cursor (ADR-0034): one step per result, before its emit; a stop ends the
 * loop at once, with no further result or step. Each loop gets its arguments and the Many's loop slots in one new
 * array (ADR-0044).
 */
public final class IntrinsicManyOp extends Op {
  /** The array of every loop with no argument and no loop slot: it has nothing to write, so the loops share it. */
  private static final Object[] NO_ARGS = {};

  private final Many many;
  private final Argument[] args;

  /** The arguments and the loop slots. */
  private final int width;

  public IntrinsicManyOp(Many many, Argument[] args) {
    this.many = many;
    this.args = args;
    this.width = args.length + many.loopSlots();
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] values = NO_ARGS;
    if (width != 0) {
      values = new Object[width];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].read(slots, in);
      }
    }
    for (long at = many.first(state, in, values); at != Many.END; at = many.next(state, in, values, at)) {
      state.tick();
      if (!out.emit(many.value(state, in, values, at))) {
        return false;
      }
    }
    return true;
  }
}
