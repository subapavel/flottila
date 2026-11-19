package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code foreach}: for each result of the init, the state slot takes it and {@code each} runs on the input, emitting
 * each of its results; a stop or a break passes at once. No step of its own. In a path variant the init and
 * {@code each} run at the Foreach's own path, the init's paths dropped, since the state stays untracked, and each result
 * of {@code each} goes on with its own path.
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
   * result: each result of the init sets the slot and runs it, for its ticks and writes, and the Foreach gives none.
   *
   * @throws IllegalStateException when {@code each} gives a result there: the facts that made the Foreach value form
   *     are wrong, and returning would lose a result
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
            if (each.eval(state, slots, in) != CallForm.NONE) {
              throw new IllegalStateException("A value-form Foreach's each gave a result after its many init");
            }
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

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    return init.runPath(
        state,
        slots,
        in,
        at,
        (r, p) -> {
          slots[slot] = r;
          return each.runPath(state, slots, in, at, out);
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
          d.startPath(each, producer, slots, in, at, out);
        });
  }
}
