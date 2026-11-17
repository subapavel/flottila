package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A call of an {@link Fn0}: its result goes to the sink, whose answer is the run's; {@link CallForm#NONE} is none. */
public final class IntrinsicOp extends Op {
  private final Fn0 fn;

  public IntrinsicOp(boolean valueForm, Fn0 fn) {
    super(valueForm);
    this.fn = fn;
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
