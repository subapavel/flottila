package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A slot of a closure's defining frame, or of one further out: the value of cell {@code cell} of the frame
 * {@code level} links out from the running one, its one result whatever the input. In a path variant its path is the
 * one kept in its path cell of that frame, and untracked for a cell that keeps none.
 */
public final class OuterVarOp extends Op {
  private final int level;
  private final int cell;

  /** The cell of the same frame that keeps the cell's path, or -1 when it keeps none. */
  private final int pathCell;

  public OuterVarOp(boolean valueForm, int level, int cell) {
    this(valueForm, level, cell, -1);
  }

  /** The Var of {@code cell} of the frame {@code level} links out, whose path waits in {@code pathCell} there, or -1. */
  public OuterVarOp(boolean valueForm, int level, int cell, int pathCell) {
    super(valueForm);
    this.level = level;
    this.cell = cell;
    this.pathCell = pathCell;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(Frames.out(slots, level)[cell]);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return Frames.out(slots, level)[cell];
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, Frames.out(slots, level)[cell]);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Object[] frame = Frames.out(slots, level);
    return out.emit(frame[cell], pathCell < 0 ? null : (Path) frame[pathCell]);
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Object[] frame = Frames.out(slots, level);
    t.emitPath(out, under, frame[cell], pathCell < 0 ? null : (Path) frame[pathCell]);
  }
}
