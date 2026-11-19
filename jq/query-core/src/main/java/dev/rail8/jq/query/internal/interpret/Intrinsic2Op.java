package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A call of an {@link Fn2} on its two arguments, in order: its result goes to the sink; {@link CallForm#NONE} is none.
 * In a path variant its result's path is made by its operation's path rule, with the two arguments as key and end.
 */
public final class Intrinsic2Op extends Op {
  private final Fn2 fn;
  private final Argument a;
  private final Argument b;
  private final ResultPath rule;

  /** The form's path side, for a rule that steps; null for any other. */
  private final PathStep step;

  /** A call whose result is untracked in a path variant. */
  public Intrinsic2Op(boolean valueForm, Fn2 fn, Argument a, Argument b) {
    this(valueForm, fn, a, b, ResultPath.UNTRACKED);
  }

  /** A call whose result's path is made by {@code rule}; a rule that steps needs a form that is a {@link PathStep}. */
  public Intrinsic2Op(boolean valueForm, Fn2 fn, Argument a, Argument b, ResultPath rule) {
    super(valueForm);
    this.fn = fn;
    this.a = a;
    this.b = b;
    this.rule = rule;
    this.step = rule.steps() ? (PathStep) fn : null;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = fn.apply(state, in, a.read(slots, in), b.read(slots, in));
    return v == CallForm.NONE || out.emit(v);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return fn.apply(state, in, a.read(slots, in), b.read(slots, in));
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    IntrinsicOp.result(t, under, fn.apply(t.state, in, a.read(slots, in), b.read(slots, in)), out);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Object k = a.read(slots, in);
    Object e = b.read(slots, in);
    rule.check(step, state, at, in, k, e);
    Object v = fn.apply(state, in, k, e);
    return v == CallForm.NONE || out.emit(v, rule.of(step, state, at, in, k, e));
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Object k = a.read(slots, in);
    Object e = b.read(slots, in);
    rule.check(step, t.state, at, in, k, e);
    Object v = fn.apply(t.state, in, k, e);
    if (v == CallForm.NONE) {
      t.answer(under, true);
    } else {
      t.emitPath(out, under, v, rule.of(step, t.state, at, in, k, e));
    }
  }
}
