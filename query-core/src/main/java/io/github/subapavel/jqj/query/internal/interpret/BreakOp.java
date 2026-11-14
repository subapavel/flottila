package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code break $name} (ADR-0008, ADR-0039): marks the token its Label's run put in the slot and returns false, with no
 * result and no step.
 */
public final class BreakOp extends Op {
  private final int slot;

  public BreakOp(int slot) {
    this.slot = slot;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    state.breakTo((Long) slots[slot]);
    return false;
  }
}
