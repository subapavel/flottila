package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A slot's value: its one result, whatever the input. In a path variant its path is the one kept in its path cell, the
 * hidden cell of an item's slot, and untracked for any other slot.
 */
public final class VarOp extends Op {
  private final int slot;

  /** The cell that keeps the slot's path, or -1 when the slot keeps none. */
  private final int pathCell;

  /** The Var of {@code slot}, whose path waits in {@code pathCell}, or of a slot that keeps none for -1. */
  public VarOp(boolean valueForm, int slot, int pathCell) {
    super(valueForm);
    this.slot = slot;
    this.pathCell = pathCell;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(slots[slot]);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return slots[slot];
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, slots[slot]);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    return out.emit(slots[slot], pathCell < 0 ? null : (Path) slots[pathCell]);
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    t.emitPath(out, under, slots[slot], pathCell < 0 ? null : (Path) slots[pathCell]);
  }
}
