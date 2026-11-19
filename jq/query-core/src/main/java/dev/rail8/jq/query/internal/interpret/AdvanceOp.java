package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * The step of a loop: takes the state slot's value v, leaving null, and runs the update on v, its own input ignored;
 * each result is stored in the slot, then emitted. A retried alternative around it finds the slot as the
 * failed attempt left it: nothing is rolled back. No step of its own. In a path variant the update runs in path mode
 * on the state, which is untracked, and each result goes on untracked.
 */
public final class AdvanceOp extends Op {
  private final int slot;
  private final Op update;

  public AdvanceOp(boolean valueForm, int slot, Op update) {
    super(valueForm);
    this.slot = slot;
    this.update = update;
  }

  /**
   * The update in push form: an Advance has its update's cardinality and may break out exactly when it does, so a
   * push-form Advance's update is never value form.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = slots[slot];
    slots[slot] = null;
    return update.run(
        state,
        slots,
        v,
        u -> {
          slots[slot] = u;
          return out.emit(u);
        });
  }

  /** The update's value on the slot's, stored in the slot; with none, the slot keeps the null the take left. */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    Object v = slots[slot];
    slots[slot] = null;
    Object u = update.eval(state, slots, v);
    if (u != CallForm.NONE) {
      slots[slot] = u;
    }
    return u;
  }

  /** Each result of the update is stored in the slot, then goes on as a result of the form that produced it. */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Object v = slots[slot];
    slots[slot] = null;
    t.start(
        update,
        under,
        slots,
        v,
        (d, producer, u) -> {
          slots[slot] = u;
          d.emit(out, producer, u);
        });
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Object v = slots[slot];
    slots[slot] = null;
    return update.runPath(
        state,
        slots,
        v,
        null,
        (u, p) -> {
          slots[slot] = u;
          return out.emit(u, null);
        });
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Object v = slots[slot];
    slots[slot] = null;
    t.startPath(
        update,
        under,
        slots,
        v,
        null,
        (d, producer, u, p) -> {
          slots[slot] = u;
          d.emitPath(out, producer, u, null);
        });
  }
}
