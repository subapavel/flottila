package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code value as $slot | body}: each result of the value side sets the slot and runs the body on the Bind's own
 * input; a stop in the body ends the value side too. In a path variant the body runs at the Bind's own path, and the
 * value side runs in value mode or, when it is tracked, in path mode, its paths dropped or, for an item, kept in the
 * slot's path cell.
 */
public final class BindOp extends Op {
  private final Op value;

  /** Whether the value side runs in path mode in a path variant. */
  private final boolean tracked;

  private final int slot;

  /** The cell that keeps the path of the value in the slot, or -1 when the path is dropped. */
  private final int pathCell;

  private final Op body;

  /** A Bind whose value side runs in value mode in a path variant too. */
  public BindOp(boolean valueForm, Op value, int slot, Op body) {
    this(valueForm, value, false, slot, -1, body);
  }

  /**
   * A Bind whose value side runs in path mode in a path variant when {@code tracked}, each result's path kept in
   * {@code pathCell}, or dropped for -1.
   */
  public BindOp(boolean valueForm, Op value, boolean tracked, int slot, int pathCell, Op body) {
    super(valueForm);
    this.value = value;
    this.tracked = tracked;
    this.slot = slot;
    this.pathCell = pathCell;
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

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    if (tracked) {
      return value.runPath(
          state,
          slots,
          in,
          at,
          (v, p) -> {
            slots[slot] = v;
            if (pathCell >= 0) {
              slots[pathCell] = p;
            }
            return body.runPath(state, slots, in, at, out);
          });
    }
    Sink rest =
        v -> {
          slots[slot] = v;
          return body.runPath(state, slots, in, at, out);
        };
    if (!value.valueForm) {
      return value.run(state, slots, in, rest);
    }
    Object v = value.eval(state, slots, in);
    return v == CallForm.NONE || rest.emit(v);
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    if (tracked) {
      t.startPath(
          value,
          under,
          slots,
          in,
          at,
          (d, producer, v, p) -> {
            slots[slot] = v;
            if (pathCell >= 0) {
              slots[pathCell] = p;
            }
            d.startPath(body, producer, slots, in, at, out);
          });
      return;
    }
    t.start(
        value,
        under,
        slots,
        in,
        (d, producer, v) -> {
          slots[slot] = v;
          d.startPath(body, producer, slots, in, at, out);
        });
  }
}
