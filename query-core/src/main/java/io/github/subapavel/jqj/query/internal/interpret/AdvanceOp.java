package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * The step of a loop (ADR-0041): takes the state slot's value v, leaving null, and runs the update on v, its own input
 * ignored; each result is stored in the slot, then emitted. A retried alternative around it finds the slot as the
 * failed attempt left it: nothing is rolled back. No step of its own.
 */
public final class AdvanceOp extends Op {
  private final int slot;
  private final Op update;

  public AdvanceOp(int slot, Op update) {
    this.slot = slot;
    this.update = update;
  }

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
}
