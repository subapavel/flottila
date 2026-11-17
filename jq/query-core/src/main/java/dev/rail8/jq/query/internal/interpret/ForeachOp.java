package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code foreach} (ADR-0041): for each result of the init, the state slot takes it and {@code each} runs on the input,
 * emitting each of its results; a stop or a break passes at once (ADR-0039). No step of its own.
 */
public final class ForeachOp extends Op {
  private final Op init;
  private final int slot;
  private final Op each;

  public ForeachOp(boolean valueForm, Op init, int slot, Op each) {
    super(valueForm);
    this.init = init;
    this.slot = slot;
    this.each = each;
  }

  /** Each part in its run form: a value-form init's value runs {@code each} after it returned. */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Sink step =
        each.valueForm
            ? r -> {
              slots[slot] = r;
              Object u = each.eval(state, slots, in);
              return u == CallForm.NONE || out.emit(u);
            }
            : r -> {
              slots[slot] = r;
              return each.run(state, slots, in, out);
            };
    if (!init.valueForm) {
      return init.run(state, slots, in, step);
    }
    Object r = init.eval(state, slots, in);
    return r == CallForm.NONE || step.emit(r);
  }

  /**
   * The value of {@code each} with the slot set. A push init of a value-form Foreach is many, so {@code each} gives no
   * result (ADR-0007 #112): each result of the init sets the slot and runs it, for its ticks and writes, and the Foreach
   * gives none.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    if (!init.valueForm) {
      init.run(
          state,
          slots,
          in,
          r -> {
            slots[slot] = r;
            each.eval(state, slots, in);
            return true;
          });
      return CallForm.NONE;
    }
    Object r = init.eval(state, slots, in);
    if (r == CallForm.NONE) {
      return r;
    }
    slots[slot] = r;
    return each.eval(state, slots, in);
  }

  /** Each result of the init sets the slot and starts {@code each} above the form that produced it. */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(
        init,
        under,
        slots,
        in,
        (d, producer, r) -> {
          slots[slot] = r;
          d.start(each, producer, slots, in, out);
        });
  }
}
