package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A call of an {@link Fn0}: its result goes to the sink, whose answer is the run's; {@link CallForm#NONE} is none. In a
 * path variant its result's path is made by its operation's path rule.
 */
public final class IntrinsicOp extends Op {
  private final Fn0 fn;
  private final ResultPath rule;

  /** The form's path side, for a rule that steps; null for any other. */
  private final PathStep step;

  /** A call whose result is untracked in a path variant. */
  public IntrinsicOp(boolean valueForm, Fn0 fn) {
    this(valueForm, fn, ResultPath.UNTRACKED);
  }

  /** A call whose result's path is made by {@code rule}; a rule that steps needs a form that is a {@link PathStep}. */
  public IntrinsicOp(boolean valueForm, Fn0 fn, ResultPath rule) {
    super(valueForm);
    this.fn = fn;
    this.rule = rule;
    this.step = rule.steps() ? (PathStep) fn : null;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = fn.apply(state, in);
    return v == CallForm.NONE || out.emit(v);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return fn.apply(state, in);
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    result(t, under, fn.apply(t.state, in), out);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    rule.check(step, state, at, in, null, null);
    Object v = fn.apply(state, in);
    return v == CallForm.NONE || out.emit(v, rule.of(step, state, at, in, null, null));
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    rule.check(step, t.state, at, in, null, null);
    Object v = fn.apply(t.state, in);
    if (v == CallForm.NONE) {
      t.answer(under, true);
    } else {
      t.emitPath(out, under, v, rule.of(step, t.state, at, in, null, null));
    }
  }

  /**
   * An intrinsic's heap form after its call: {@link CallForm#NONE} answers {@code under} that it ran out, and any other
   * {@code v} goes to {@code out}.
   */
  static void result(Trampoline t, HeapForm under, Object v, HeapSink out) {
    if (v == CallForm.NONE) {
      t.answer(under, true);
    } else {
      t.emit(out, under, v);
    }
  }
}
