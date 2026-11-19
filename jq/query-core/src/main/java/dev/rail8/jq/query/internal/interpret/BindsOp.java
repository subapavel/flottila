package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code v1 as $s1 | … | vn as $sn | body}, run as the chain of nested {@link BindOp}s it means. A value-form value
 * returns its value, which sets its slot, and the loop goes on to the next value; with no result the Binds runs out
 * there. In the conservative compile a tail value runs into a {@link Holder} the same way. Any other
 * value runs the rest, the later values and the body, inside its emit, as a BindOp does. So the stack grows by one
 * recursion per generator value, not per value. Each value and the body run on the Binds' own input; no step of its
 * own. Its heap form keeps no state: each value's sink starts what comes after it.
 *
 * <p>In a path variant the body runs at the Binds' own path, an untracked value as above and a tracked one in path mode,
 * its paths dropped: a value-form or tail one into the holder, any other with the rest in its emit.
 */
public final class BindsOp extends Op {
  private final Op[] values;

  /**
   * Whether each value is a tail emitter: one result at most, and it never breaks out. A value-form value runs by its
   * value form first, so only the conservative compile reads this.
   */
  private final boolean[] tails;

  /** Whether each value runs in path mode in a path variant. */
  private final boolean[] tracked;

  /** The slot each value sets. */
  private final int[] targets;

  private final Op body;

  /** A Binds whose values run in value mode in a path variant too. */
  public BindsOp(boolean valueForm, Op[] values, boolean[] tails, int[] targets, Op body) {
    this(valueForm, values, tails, new boolean[values.length], targets, body);
  }

  /** A Binds whose value {@code k} runs in path mode in a path variant when {@code tracked[k]}. */
  public BindsOp(boolean valueForm, Op[] values, boolean[] tails, boolean[] tracked, int[] targets, Op body) {
    super(valueForm);
    this.values = values;
    this.tails = tails;
    this.tracked = tracked;
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

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    return fromPath(0, state, slots, in, at, out, new Holder());
  }

  /** {@link #from} in the path variant: the body runs at {@code at}, and each tracked value in path mode. */
  private boolean fromPath(int first, RunState state, Object[] slots, Object in, Path at, PathSink out, Holder holder) {
    for (int k = first; k < values.length; k++) {
      int slot = targets[k];
      Op value = values[k];
      int next = k + 1;
      if (tracked[k] && !value.valueForm && !tails[k]) {
        return value.runPath(
            state,
            slots,
            in,
            at,
            (v, p) -> {
              slots[slot] = v;
              return fromPath(next, state, slots, in, at, out, holder);
            });
      }
      if (tracked[k]) {
        // One result at most, and it never breaks out: the holder's stop is that result, as for a tail value.
        if (value.runPath(state, slots, in, at, holder)) {
          return true;
        }
        slots[slot] = holder.take();
        continue;
      }
      if (value.valueForm) {
        Object v = value.eval(state, slots, in);
        if (v == CallForm.NONE) {
          return true;
        }
        slots[slot] = v;
        continue;
      }
      if (!tails[k]) {
        return value.run(
            state,
            slots,
            in,
            v -> {
              slots[slot] = v;
              return fromPath(next, state, slots, in, at, out, holder);
            });
      }
      if (value.run(state, slots, in, holder)) {
        return true;
      }
      slots[slot] = holder.take();
    }
    return body.runPath(state, slots, in, at, out);
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    fromPath(0, t, under, slots, in, at, out);
  }

  /** {@link #from}'s heap form in the path variant: each tracked value's path variant starts, and the body at {@code at}. */
  private void fromPath(int k, Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    if (k == values.length) {
      t.startPath(body, under, slots, in, at, out);
      return;
    }
    if (tracked[k]) {
      t.startPath(
          values[k],
          under,
          slots,
          in,
          at,
          (d, producer, v, p) -> {
            slots[targets[k]] = v;
            fromPath(k + 1, d, producer, slots, in, at, out);
          });
      return;
    }
    t.start(
        values[k],
        under,
        slots,
        in,
        (d, producer, v) -> {
          slots[targets[k]] = v;
          fromPath(k + 1, d, producer, slots, in, at, out);
        });
  }
}
