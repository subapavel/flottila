package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code reduce} (ADR-0041): for each result of the init, the state slot takes it and {@code each} runs on the input,
 * its results dropped; then the slot's value is emitted, the slot nulled first, so the run holds the state no longer.
 * A false from {@code each} is a break, since the sink it gets never stops: it passes at once, with no emit
 * (ADR-0039). No step of its own.
 */
public final class ReduceOp extends Op {
  /** The sink of {@code each}: the Advance has stored each result already, so the Reduce keeps none. */
  private static final Sink DROP = v -> true;

  /** The heap form of {@link #DROP}: each result of {@code each} is answered to go on. */
  private static final HeapSink HEAP_DROP = (t, producer, v) -> t.answer(producer, true);

  private final Op init;
  private final int slot;
  private final Op each;

  public ReduceOp(Op init, int slot, Op each) {
    this.init = init;
    this.slot = slot;
    this.each = each;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return init.run(
        state,
        slots,
        in,
        r -> {
          slots[slot] = r;
          if (!each.run(state, slots, in, DROP)) {
            return false;
          }
          Object last = slots[slot];
          slots[slot] = null;
          return out.emit(last);
        });
  }

  /**
   * Each result of the init sets the slot and starts {@code each} under a {@link Step}, above the form that produced
   * it.
   */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(
        init,
        under,
        slots,
        in,
        (d, producer, r) -> {
          slots[slot] = r;
          d.start(each, new Step(producer, slots, out), slots, in, HEAP_DROP);
        });
  }

  /**
   * The heap form while {@code each} runs on one result of the init: when it ran out, the slot's value is emitted, the
   * slot nulled first, as a result of the form that produced the init's result; a break passes at once.
   */
  private final class Step extends HeapForm {
    private final Object[] slots;
    private final HeapSink out;

    Step(HeapForm producer, Object[] slots, HeapSink out) {
      super(producer);
      this.slots = slots;
      this.out = out;
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      Object last = slots[slot];
      slots[slot] = null;
      t.emit(out, under, last);
    }
  }
}
