package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A tail call, TAIL_SELF or TAIL_GROUP, as a pending jump: it ticks one step, as every tail jump does so that a chain
 * of jumps still spends the step budget, sets the jump in the run state, itself with the running frame and its input,
 * and returns at once: true from {@link #run}, NONE from {@link #eval}, and an answer of true on the driver.
 * It enters no activation and adds no units. The entry loop of the activation running in that frame, a {@link CallOp}'s
 * or its {@link CallOp.Active} form's, takes the jump: it builds the callee's frame with {@link #frame} and runs the
 * callee's body in the same activation, so neither the Java stack nor the live activations grow along a chain of jumps.
 *
 * <p>The frame tells the jump's activation apart from any other: a call that is a tail call's left context, at most one,
 * runs the tail call inside its own result's emit, so its entry loop sees the jump first and must leave it to the
 * activation it came from.
 *
 * <p>In a path variant the jump first writes its input's path into the running frame's input-path cell, where the
 * entry loop reads it to run the callee's path variant at that path.
 */
public final class JumpOp extends Op {
  private final LiftedFunction fn;
  private final ClosureArgument[] closures;
  private final Argument[] values;

  /** The running frame's input-path cell, or -1 when it has none. */
  private final int pathCell;

  /**
   * A jump to {@code fn}, its value arguments {@code values} and its closure arguments {@code closures}, each a closure
   * parameter passed on or {@link ClosureArgument#closed}, from a frame whose input-path cell is {@code pathCell}, or -1
   * when it has none.
   */
  public JumpOp(boolean valueForm, LiftedFunction fn, ClosureArgument[] closures, Argument[] values, int pathCell) {
    super(valueForm);
    this.fn = fn;
    this.closures = closures;
    this.values = values;
    this.pathCell = pathCell;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    state.tick();
    state.jump(this, slots, in);
    return true;
  }

  /** As {@link #run}: the jump gives no result here; the callee's body gives the activation's. */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    state.tick();
    state.jump(this, slots, in);
    return CallForm.NONE;
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.state.tick();
    t.state.jump(this, slots, in);
    t.answer(under, true);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    state.tick();
    slots[pathCell] = at;
    state.jump(this, slots, in);
    return true;
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    t.state.tick();
    slots[pathCell] = at;
    t.state.jump(this, slots, in);
    t.answer(under, true);
  }

  /** The function the jump enters. */
  LiftedFunction fn() {
    return fn;
  }

  /** The callee's frame: its value parameters read on {@code in} and its closures, bound in {@code from}, the jump's frame. */
  Object[] frame(Object[] from, Object in) {
    return fn.frame(values, closures, from, in);
  }

  /**
   * The callee's frame for the path variant, as {@link #frame}, its input-path cell holding the path the jump wrote
   * into {@code from}'s.
   */
  Object[] framePath(Object[] from, Object in) {
    Object[] frame = fn.frame(values, closures, from, in);
    frame[fn.pathCell()] = from[pathCell];
    return frame;
  }
}
