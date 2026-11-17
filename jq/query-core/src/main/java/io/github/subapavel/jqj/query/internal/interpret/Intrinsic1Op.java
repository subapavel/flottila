package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A call of an {@link Fn1} on its argument: its result goes to the sink; {@link CallForm#NONE} is none. */
public final class Intrinsic1Op extends Op {
  private final Fn1 fn;
  private final Argument a;

  public Intrinsic1Op(boolean valueForm, Fn1 fn, Argument a) {
    super(valueForm);
    this.fn = fn;
    this.a = a;
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
}
