package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code reduce}: for each result of the init, the state slot takes it and {@code each} runs on the input, its
 * results dropped; then the slot's value is emitted, the slot nulled first, so the run holds the state no longer. A
 * false from {@code each} is a break, since the sink it gets never stops: it passes at once, with no emit. No step of
 * its own. In a path variant the init and {@code each} run at the Reduce's own path, and the state stays untracked: the
 * init's paths are dropped and the slot's value is emitted untracked.
 */
public final class ReduceOp extends Op {
  /** The heap form of {@link Op#DROP}, the sink of {@code each}: each result is answered to go on. */
  private static final HeapSink HEAP_DROP = (t, producer, v) -> t.answer(producer, true);

  /** The heap form of {@link Op#DROP_PATH}. */
  private static final HeapPathSink HEAP_DROP_PATH = (t, producer, v, p) -> t.answer(producer, true);

  private final Op init;
  private final int slot;
  private final Op each;

  public ReduceOp(boolean valueForm, Op init, int slot, Op each) {
    super(valueForm);
    this.init = init;
    this.slot = slot;
    this.each = each;
  }

  /** Each part in its run form: a value-form init's value runs the step after it returned. */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Sink step = step(state, slots, in, out);
    if (!init.valueForm) {
      return init.run(state, slots, in, step);
    }
    Object r = init.eval(state, slots, in);
    return r == CallForm.NONE || step.emit(r);
  }

  /**
   * The sink of the init's results: each sets the slot and runs {@code each}, its results dropped as {@link Op#DROP}
   * drops them; a value-form {@code each} returns its result instead, with no break. Then the slot's value goes to
   * {@code out}, the slot nulled first.
   */
  private Sink step(RunState state, Object[] slots, Object in, Sink out) {
    return r -> {
      slots[slot] = r;
      if (each.valueForm) {
        each.eval(state, slots, in);
      } else if (!each.run(state, slots, in, DROP)) {
        return false;
      }
      Object last = slots[slot];
      slots[slot] = null;
      return out.emit(last);
    };
  }

  /**
   * The slot's value after {@code each} ran on the init's value. A push init of a value-form Reduce is many, so
   * {@code each} ends no run normally: each result of the init runs the step, and the Reduce gives none.
   * A push {@code each} breaks only inside itself, so its run's answer is true.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    if (!init.valueForm) {
      init.run(state, slots, in, step(state, slots, in, DROP));
      return CallForm.NONE;
    }
    Object r = init.eval(state, slots, in);
    if (r == CallForm.NONE) {
      return r;
    }
    slots[slot] = r;
    if (each.valueForm) {
      each.eval(state, slots, in);
    } else {
      each.run(state, slots, in, DROP);
    }
    Object last = slots[slot];
    slots[slot] = null;
    return last;
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
          d.start(each, new Step(producer, slots, out, null), slots, in, HEAP_DROP);
        });
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    return init.runPath(
        state,
        slots,
        in,
        at,
        (r, p) -> {
          slots[slot] = r;
          if (!each.runPath(state, slots, in, at, DROP_PATH)) {
            return false;
          }
          Object last = slots[slot];
          slots[slot] = null;
          return out.emit(last, null);
        });
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    t.startPath(
        init,
        under,
        slots,
        in,
        at,
        (d, producer, r, p) -> {
          slots[slot] = r;
          d.startPath(each, new Step(producer, slots, null, out), slots, in, at, HEAP_DROP_PATH);
        });
  }

  /**
   * The heap form while {@code each} runs on one result of the init: when it ran out, the slot's value is emitted, the
   * slot nulled first, as a result of the form that produced the init's result, into {@code out} or untracked into
   * {@code paths}; a break passes at once.
   */
  private final class Step extends HeapForm {
    private final Object[] slots;
    private final HeapSink out;
    private final HeapPathSink paths;

    Step(HeapForm producer, Object[] slots, HeapSink out, HeapPathSink paths) {
      super(producer);
      this.slots = slots;
      this.out = out;
      this.paths = paths;
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      Object last = slots[slot];
      slots[slot] = null;
      if (paths != null) {
        t.emitPath(paths, under, last, null);
      } else {
        t.emit(out, under, last);
      }
    }
  }
}
