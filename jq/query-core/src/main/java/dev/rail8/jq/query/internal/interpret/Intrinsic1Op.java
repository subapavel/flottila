package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A call of an {@link Fn1} on its argument: its result goes to the sink; {@link CallForm#NONE} is none. In a path
 * variant its result's path is made by its operation's path rule, with the argument as the key.
 */
public final class Intrinsic1Op extends Op {
  private final Fn1 fn;
  private final Argument a;
  private final ResultPath rule;

  /** The form's path side, for a rule that steps; null for any other. */
  private final PathStep step;

  /** A call whose result is untracked in a path variant. */
  public Intrinsic1Op(boolean valueForm, Fn1 fn, Argument a) {
    this(valueForm, fn, a, ResultPath.UNTRACKED);
  }

  /** A call whose result's path is made by {@code rule}; a rule that steps needs a form that is a {@link PathStep}. */
  public Intrinsic1Op(boolean valueForm, Fn1 fn, Argument a, ResultPath rule) {
    super(valueForm);
    this.fn = fn;
    this.a = a;
    this.rule = rule;
    this.step = rule.steps() ? (PathStep) fn : null;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = fn.apply(state, in, a.read(slots, in));
    return v == CallForm.NONE || out.emit(v);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return fn.apply(state, in, a.read(slots, in));
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    IntrinsicOp.result(t, under, fn.apply(t.state, in, a.read(slots, in)), out);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Object k = a.read(slots, in);
    rule.check(step, state, at, in, k, null);
    Object v = fn.apply(state, in, k);
    return v == CallForm.NONE || out.emit(v, rule.of(step, state, at, in, k, null));
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Object k = a.read(slots, in);
    rule.check(step, t.state, at, in, k, null);
    Object v = fn.apply(t.state, in, k);
    if (v == CallForm.NONE) {
      t.answer(under, true);
    } else {
      t.emitPath(out, under, v, rule.of(step, t.state, at, in, k, null));
    }
  }
}
