package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code value as $slot | body}: each result of the value side sets the slot and runs the body on the Bind's own
 * input; a stop in the body ends the value side too.
 */
public final class BindOp extends Op {
  private final Op value;
  private final int slot;
  private final Op body;

  public BindOp(boolean valueForm, Op value, int slot, Op body) {
    super(valueForm);
    this.value = value;
    this.slot = slot;
    this.body = body;
  }

  /** Each part in its run form: a value-form value side's value sets the slot and runs the body after it returned. */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Sink rest =
        body.valueForm
            ? v -> {
              slots[slot] = v;
              Object w = body.eval(state, slots, in);
              return w == CallForm.NONE || out.emit(w);
            }
            : v -> {
              slots[slot] = v;
              return body.run(state, slots, in, out);
            };
    if (!value.valueForm) {
      return value.run(state, slots, in, rest);
    }
    Object v = value.eval(state, slots, in);
    return v == CallForm.NONE || rest.emit(v);
  }

  /**
   * The body's value with the slot set. A push value side of a value-form Bind is many, so the body gives no result:
   * each result of the value side sets the slot and runs it, for its ticks and writes, and the Bind gives none.
   *
   * @throws IllegalStateException when the body gives a result there: the facts that made the Bind value form are
   *     wrong, and returning would lose a result
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    if (!value.valueForm) {
      value.run(
          state,
          slots,
          in,
          v -> {
            slots[slot] = v;
            if (body.eval(state, slots, in) != CallForm.NONE) {
              throw new IllegalStateException("A value-form Bind's body gave a result after its many value side");
            }
            return true;
          });
      return CallForm.NONE;
    }
    Object v = value.eval(state, slots, in);
    if (v == CallForm.NONE) {
      return v;
    }
    slots[slot] = v;
    return body.eval(state, slots, in);
  }

  /** Each result of the value side sets the slot and starts the body above the form that produced it. */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(
        value,
        under,
        slots,
        in,
        (d, producer, v) -> {
          slots[slot] = v;
          d.start(body, producer, slots, in, out);
        });
  }
}
