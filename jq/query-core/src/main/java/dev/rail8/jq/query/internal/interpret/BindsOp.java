package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code v1 as $s1 | … | vn as $sn | body}, run as the chain of nested {@link BindOp}s it means. A value-form value
 * returns its value, which sets its slot, and the loop goes on to the next value; with no result the Binds runs out
 * there. In the conservative compile a tail value runs into a {@link Holder} the same way. Any other
 * value runs the rest, the later values and the body, inside its emit, as a BindOp does. So the stack grows by one
 * recursion per generator value, not per value. Each value and the body run on the Binds' own input; no step of its
 * own. Its heap form keeps no state: each value's sink starts what comes after it.
 */
public final class BindsOp extends Op {
  private final Op[] values;

  /**
   * Whether each value is a tail emitter: one result at most, and it never breaks out. A value-form value runs by its
   * value form first, so only the conservative compile reads this.
   */
  private final boolean[] tails;

  /** The slot each value sets. */
  private final int[] targets;

  private final Op body;

  public BindsOp(boolean valueForm, Op[] values, boolean[] tails, int[] targets, Op body) {
    super(valueForm);
    this.values = values;
    this.tails = tails;
    this.targets = targets;
    this.body = body;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return from(0, state, slots, in, out, new Holder());
  }

  /**
   * The values from {@code first} on, then the body, with the slots of the values before {@code first} set. The one
   * {@code holder} of the run serves every tail value: each is taken, leaving it empty, before the next one runs.
   */
  private boolean from(int first, RunState state, Object[] slots, Object in, Sink out, Holder holder) {
    for (int k = first; k < values.length; k++) {
      int slot = targets[k];
      if (values[k].valueForm) {
        Object v = values[k].eval(state, slots, in);
        if (v == CallForm.NONE) {
          return true;
        }
        slots[slot] = v;
        continue;
      }
      if (!tails[k]) {
        int next = k + 1;
        return values[k].run(
            state,
            slots,
            in,
            v -> {
              slots[slot] = v;
              return from(next, state, slots, in, out, holder);
            });
      }
      // The holder answers its one result with a stop and a tail emitter never breaks out, so a true means no result:
      // the Binds runs out. A false means the result came, and it sets the slot.
      if (values[k].run(state, slots, in, holder)) {
        return true;
      }
      slots[slot] = holder.take();
    }
    if (!body.valueForm) {
      return body.run(state, slots, in, out);
    }
    Object w = body.eval(state, slots, in);
    return w == CallForm.NONE || out.emit(w);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return evalFrom(0, state, slots, in);
  }

  /**
   * The value form of the values from {@code first} on, then the body's value. A push value of a value-form Binds is
   * many, so what comes after it gives no result: each of its results sets its slot and runs the rest, for its ticks
   * and writes, and the Binds gives none.
   *
   * @throws IllegalStateException when the rest gives a result there: the facts that made the Binds value form are
   *     wrong, and returning would lose a result
   */
  private Object evalFrom(int first, RunState state, Object[] slots, Object in) {
    for (int k = first; k < values.length; k++) {
      int slot = targets[k];
      if (!values[k].valueForm) {
        int next = k + 1;
        values[k].run(
            state,
            slots,
            in,
            v -> {
              slots[slot] = v;
              if (evalFrom(next, state, slots, in) != CallForm.NONE) {
                throw new IllegalStateException("A value-form Binds' rest gave a result after its many value");
              }
              return true;
            });
        return CallForm.NONE;
      }
      Object v = values[k].eval(state, slots, in);
      if (v == CallForm.NONE) {
        return v;
      }
      slots[slot] = v;
    }
    return body.eval(state, slots, in);
  }

  /**
   * Each result of a value sets its slot and starts the next value, or the body after the last, above the form that
   * produced it. A tail emitter has no role on the driver: a tail value starts no form of its own, so the next value
   * starts on the form the push form's loop goes on from, and nothing runs in it after its result.
   */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    from(0, t, under, slots, in, out);
  }

  /** Value {@code k} under {@code under}, its slot set by its sink; at {@code k} past the last value, the body. */
  private void from(int k, Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    if (k == values.length) {
      t.start(body, under, slots, in, out);
      return;
    }
    t.start(
        values[k],
        under,
        slots,
        in,
        (d, producer, v) -> {
          slots[targets[k]] = v;
          from(k + 1, d, producer, slots, in, out);
        });
  }
}
