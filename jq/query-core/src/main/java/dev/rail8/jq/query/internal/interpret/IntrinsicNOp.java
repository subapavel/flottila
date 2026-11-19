package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A call of an {@link FnN} on its arguments, read into an array that is new for each call, so the operation may keep
 * it: its result goes to the sink; {@link CallForm#NONE} is none. In a path variant its result is untracked or, when
 * its operation passes the path on, has the input's.
 */
public final class IntrinsicNOp extends Op {
  private final FnN fn;
  private final Argument[] args;

  /** Whether the result has the input's path in a path variant. */
  private final boolean same;

  /** A call whose result is untracked in a path variant. */
  public IntrinsicNOp(boolean valueForm, FnN fn, Argument[] args) {
    this(valueForm, fn, args, false);
  }

  /** A call whose result has the input's path in a path variant when {@code same}, and is untracked otherwise. */
  public IntrinsicNOp(boolean valueForm, FnN fn, Argument[] args, boolean same) {
    super(valueForm);
    this.fn = fn;
    this.args = args;
    this.same = same;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = fn.apply(state, in, values(slots, in));
    return v == CallForm.NONE || out.emit(v);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return fn.apply(state, in, values(slots, in));
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    IntrinsicOp.result(t, under, fn.apply(t.state, in, values(slots, in)), out);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Object v = fn.apply(state, in, values(slots, in));
    return v == CallForm.NONE || out.emit(v, same ? at : null);
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Object v = fn.apply(t.state, in, values(slots, in));
    if (v == CallForm.NONE) {
      t.answer(under, true);
    } else {
      t.emitPath(out, under, v, same ? at : null);
    }
  }

  /** The arguments' values for {@code in}, in a new array. */
  private Object[] values(Object[] slots, Object in) {
    Object[] values = new Object[args.length];
    for (int i = 0; i < values.length; i++) {
      values[i] = args[i].read(slots, in);
    }
    return values;
  }
}
