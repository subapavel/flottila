package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/** {@code left | right}: each result of the left side runs the right side, and a stop on the right comes back. */
public final class PipeOp extends Op {
  private final Op left;
  private final Op right;

  public PipeOp(boolean valueForm, Op left, Op right) {
    super(valueForm);
    this.left = left;
    this.right = right;
  }

  /** Each side in its run form: a value-form left side's value runs the right side here, after the left returned. */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Sink rest =
        right.valueForm
            ? v -> {
              Object w = right.eval(state, slots, v);
              return w == CallForm.NONE || out.emit(w);
            }
            : v -> right.run(state, slots, v, out);
    if (!left.valueForm) {
      return left.run(state, slots, in, rest);
    }
    Object v = left.eval(state, slots, in);
    return v == CallForm.NONE || rest.emit(v);
  }

  /**
   * The right side's value on the left side's. A push left side of a value-form Pipe is many, so the right side gives
   * no result: each result of the left runs it, for its ticks and writes, and the Pipe gives none.
   *
   * @throws IllegalStateException when the right side gives a result there: the facts that made the Pipe value form are
   *     wrong, and returning would lose a result
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    if (!left.valueForm) {
      left.run(
          state,
          slots,
          in,
          v -> {
            if (right.eval(state, slots, v) != CallForm.NONE) {
              throw new IllegalStateException("A value-form Pipe's right side gave a result after its many left side");
            }
            return true;
          });
      return CallForm.NONE;
    }
    Object v = left.eval(state, slots, in);
    return v == CallForm.NONE ? v : right.eval(state, slots, v);
  }

  /** Each result of the left side starts the right side above the form that produced it. */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(left, under, slots, in, (d, producer, v) -> d.start(right, producer, slots, v, out));
  }

  /** Each result of the left side runs the right side at its path. */
  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    return left.runPath(state, slots, in, at, (v, p) -> right.runPath(state, slots, v, p, out));
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    t.startPath(left, under, slots, in, at, (d, producer, v, p) -> d.startPath(right, producer, slots, v, p, out));
  }
}
