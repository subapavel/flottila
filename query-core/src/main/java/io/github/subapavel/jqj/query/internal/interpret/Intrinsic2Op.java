package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A call of an {@link Fn2} on its two arguments, in order: its result goes to the sink; {@link CallForm#NONE} is none. */
public final class Intrinsic2Op extends Op {
  private final Fn2 fn;
  private final Argument a;
  private final Argument b;

  public Intrinsic2Op(Fn2 fn, Argument a, Argument b) {
    this.fn = fn;
    this.a = a;
    this.b = b;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = fn.apply(state, in, a.read(slots, in), b.read(slots, in));
    return v == CallForm.NONE || out.emit(v);
  }
}
