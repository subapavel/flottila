package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code value as $slot | body} (ADR-0034): each result of the value side sets the slot and runs the body on the Bind's
 * own input; a stop in the body ends the value side too.
 */
public final class BindOp extends Op {
  private final Op value;
  private final int slot;
  private final Op body;

  public BindOp(Op value, int slot, Op body) {
    this.value = value;
    this.slot = slot;
    this.body = body;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return value.run(
        state,
        slots,
        in,
        v -> {
          slots[slot] = v;
          return body.run(state, slots, in, out);
        });
  }
}
