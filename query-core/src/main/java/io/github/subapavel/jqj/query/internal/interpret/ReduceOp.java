package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code reduce} (ADR-0041): for each result of the init, the state slot takes it and {@code each} runs on the input,
 * its results dropped; then the slot's value is emitted, the slot nulled first, so the run holds the state no longer.
 * A false from {@code each} is a break, since the sink it gets never stops: it passes at once, with no emit
 * (ADR-0039). No step of its own.
 */
public final class ReduceOp extends Op {
  /** The sink of {@code each}: the Advance has stored each result already, so the Reduce keeps none. */
  private static final Sink DROP = v -> true;

  private final Op init;
  private final int slot;
  private final Op each;

  public ReduceOp(Op init, int slot, Op each) {
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
          if (!each.run(state, slots, in, DROP)) {
            return false;
          }
          Object last = slots[slot];
          slots[slot] = null;
          return out.emit(last);
        });
  }
}
