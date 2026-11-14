package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code foreach} (ADR-0041): for each result of the init, the state slot takes it and {@code each} runs on the input,
 * emitting each of its results; a stop or a break passes at once (ADR-0039). No step of its own.
 */
public final class ForeachOp extends Op {
  private final Op init;
  private final int slot;
  private final Op each;

  public ForeachOp(Op init, int slot, Op each) {
    this.init = init;
    this.slot = slot;
    this.each = each;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return init.run(
        state,
        slots,
        in,
        r -> {
          slots[slot] = r;
          return each.run(state, slots, in, out);
        });
  }
}
