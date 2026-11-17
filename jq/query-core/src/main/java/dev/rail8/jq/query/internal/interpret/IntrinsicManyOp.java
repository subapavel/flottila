package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A call of a {@link Many} as a loop over its cursor: one step per result, before its emit; a stop ends the loop at
 * once, with no further result or step. Each loop gets its arguments and the Many's loop slots in one new
 * array. Its heap form is the {@link Loop}, which keeps the cursor while each result is answered.
 */
public final class IntrinsicManyOp extends Op {
  /** The array of every loop with no argument and no loop slot: it has nothing to write, so the loops share it. */
  private static final Object[] NO_ARGS = {};

  private final Many many;
  private final Argument[] args;

  /** The arguments and the loop slots. */
  private final int width;

  public IntrinsicManyOp(Many many, Argument[] args) {
    super(false);
    this.many = many;
    this.args = args;
    this.width = args.length + many.loopSlots();
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] values = values(slots, in);
    for (long at = many.first(state, in, values); at != Many.END; at = many.next(state, in, values, at)) {
      state.tick();
      if (!out.emit(many.value(state, in, values, at))) {
        return false;
      }
    }
    return true;
  }

  /** Never called: a Many intrinsic is never eligible for the value form. */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    throw new IllegalStateException("A Many intrinsic has no value form");
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Object[] values = values(slots, in);
    new Loop(under, in, values, out).at(t, many.first(t.state, in, values));
  }

  /** The loop's array: its arguments' values for {@code in}, then room for the loop slots. */
  private Object[] values(Object[] slots, Object in) {
    Object[] values = NO_ARGS;
    if (width != 0) {
      values = new Object[width];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].read(slots, in);
      }
    }
    return values;
  }

  /** The heap form of one loop: its input, its array and the cursor of the result it emitted last. */
  private final class Loop extends HeapForm {
    private final Object in;
    private final Object[] values;
    private final HeapSink out;
    private long at;

    Loop(HeapForm under, Object in, Object[] values, HeapSink out) {
      super(under);
      this.in = in;
      this.values = values;
      this.out = out;
    }

    /** At the cursor {@code at}: past the end the loop runs out, otherwise one step and the result's emit. */
    void at(Trampoline t, long at) {
      if (at == Many.END) {
        t.answer(under, true);
        return;
      }
      this.at = at;
      t.state.tick();
      t.emit(out, this, many.value(t.state, in, values, at));
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      at(t, many.next(t.state, in, values, at));
    }
  }
}
