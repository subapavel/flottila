package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A slot's value (ADR-0034): its one result, whatever the input. */
public final class VarOp extends Op {
  private final int slot;

  public VarOp(int slot) {
    this.slot = slot;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(slots[slot]);
  }
}
