package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Truth;

/**
 * {@code if c then t else e end} (ADR-0037): each result of the condition runs one branch on the If's input, {@code t}
 * when the truth holds for it and {@code e} when it does not, and a stop in a branch ends the condition too. A tail
 * condition (ADR-0043) runs into a {@link Holder} first, and its one result, if any, runs the branch after the
 * condition returned; any other condition runs the branch inside its emit. No step of its own.
 */
public final class IfOp extends Op {
  private final Op cond;

  /** Whether the condition is a tail emitter: one result at most, and it never breaks out (ADR-0043). */
  private final boolean tail;

  private final Op then;
  private final Op otherwise;
  private final Truth truth;

  public IfOp(Op cond, boolean tail, Op then, Op otherwise, Truth truth) {
    this.cond = cond;
    this.tail = tail;
    this.then = then;
    this.otherwise = otherwise;
    this.truth = truth;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    if (!tail) {
      return cond.run(state, slots, in, v -> branch(v).run(state, slots, in, out));
    }
    Holder holder = new Holder();
    // The holder answers its one result with a stop and a tail emitter never breaks out, so a true means no result:
    // the If runs out. A false means the result came, and it runs its branch.
    if (cond.run(state, slots, in, holder)) {
      return true;
    }
    return branch(holder.take()).run(state, slots, in, out);
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
