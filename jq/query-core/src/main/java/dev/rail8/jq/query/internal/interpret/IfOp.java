package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;
import dev.rail8.jq.query.run.Truth;

/**
 * {@code if c then t else e end}: each result of the condition runs one branch on the If's input, {@code t} when the
 * truth holds for it and {@code e} when it does not, and a stop in a branch ends the condition too. A value-form
 * condition returns its value, which runs the branch after the condition returned; in the conservative compile a tail
 * condition runs into a {@link Holder} first to the same end; any other condition runs the branch inside its emit. Each
 * branch runs in its run form. No step of its own.
 */
public final class IfOp extends Op {
  private final Op cond;

  /**
   * Whether the condition is a tail emitter: one result at most, and it never breaks out. A value-form condition runs
   * by its value form first, so only the conservative compile reads this.
   */
  private final boolean tail;

  private final Op then;
  private final Op otherwise;
  private final Truth truth;

  public IfOp(boolean valueForm, Op cond, boolean tail, Op then, Op otherwise, Truth truth) {
    super(valueForm);
    this.cond = cond;
    this.tail = tail;
    this.then = then;
    this.otherwise = otherwise;
    this.truth = truth;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v;
    if (cond.valueForm) {
      v = cond.eval(state, slots, in);
      if (v == CallForm.NONE) {
        return true;
      }
    } else if (tail) {
      Holder holder = new Holder();
      // The holder answers its one result with a stop and a tail emitter never breaks out, so a true means no result:
      // the If runs out. A false means the result came, and it runs its branch.
      if (cond.run(state, slots, in, holder)) {
        return true;
      }
      v = holder.take();
    } else {
      return cond.run(
          state,
          slots,
          in,
          c -> {
            Op branch = branch(c);
            if (!branch.valueForm) {
              return branch.run(state, slots, in, out);
            }
            Object w = branch.eval(state, slots, in);
            return w == CallForm.NONE || out.emit(w);
          });
    }
    Op branch = branch(v);
    if (!branch.valueForm) {
      return branch.run(state, slots, in, out);
    }
    Object w = branch.eval(state, slots, in);
    return w == CallForm.NONE || out.emit(w);
  }

  /**
   * The branch's value. A push condition of a value-form If is many, so neither branch gives a result:
   * each result of the condition runs its branch, for its ticks and writes, and the If gives none.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    if (!cond.valueForm) {
      cond.run(
          state,
          slots,
          in,
          c -> {
            branch(c).eval(state, slots, in);
            return true;
          });
      return CallForm.NONE;
    }
    Object v = cond.eval(state, slots, in);
    return v == CallForm.NONE ? v : branch(v).eval(state, slots, in);
  }

  /**
   * Each result of the condition starts its branch above the form that produced it. A tail emitter has no role on the
   * driver: a tail condition starts no form of its own, so its one result's branch runs where the push form runs it,
   * on the If's own form, and nothing runs in it after its result.
   */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(cond, under, slots, in, (d, producer, v) -> d.start(branch(v), producer, slots, in, out));
  }

  private Op branch(Object v) {
    return truth.test(v) ? then : otherwise;
  }
}
