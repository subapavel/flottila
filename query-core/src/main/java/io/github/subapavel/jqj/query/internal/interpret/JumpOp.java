package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A tail call, TAIL_SELF or TAIL_GROUP, as a pending jump (GLOSSARY "Tail call", ADR-0047 and its #118 amendment): it
 * ticks one step, as ADR-0008 charges every tail jump, sets the jump in the run state, itself with the running frame and
 * its input, and returns at once: true from {@link #run}, NONE from {@link #eval}, and an answer of true on the driver.
 * It enters no activation and adds no units. The entry loop of the activation running in that frame, a {@link CallOp}'s
 * or its {@link CallOp.Active} form's, takes the jump: it builds the callee's frame with {@link #frame} and runs the
 * callee's body in the same activation, so neither the Java stack nor the live activations grow along a chain of jumps.
 *
 * <p>The frame tells the jump's activation apart from any other: a call that is a tail call's left context, at most one,
 * runs the tail call inside its own result's emit, so its entry loop sees the jump first and must leave it to the
 * activation it came from.
 */
public final class JumpOp extends Op {
  private final LiftedFunction fn;
  private final ClosureArgument[] closures;
  private final Argument[] values;

  /**
   * A jump to {@code fn}, its value arguments {@code values} and its closure arguments {@code closures}, each a closure
   * parameter passed on or {@link ClosureArgument#closed}.
   */
  public JumpOp(boolean valueForm, LiftedFunction fn, ClosureArgument[] closures, Argument[] values) {
    super(valueForm);
    this.fn = fn;
    this.closures = closures;
    this.values = values;
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

  /** The function the jump enters. */
  LiftedFunction fn() {
    return fn;
  }

  /** The callee's frame: its value parameters read on {@code in} and its closures, bound in {@code from}, the jump's frame. */
  Object[] frame(Object[] from, Object in) {
    return fn.frame(values, closures, from, in);
  }
}
