package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code try body catch handler} (ADR-0008, ADR-0039): the body's results pass through a sink that wraps each error
 * thrown downstream of them, so when an error leaves the body, one of depth above 0 is unwrapped and raised again, and
 * one of depth 0, the body's own, ends the body and runs the handler on its value. The handler runs outside the catch,
 * so its errors propagate. A break or a stop is a false, which passes at once; a Termination is no FilterException.
 * Entering the handler charges no step.
 */
public final class TryOp extends Op {
  private final Op body;
  private final Op handler;

  public TryOp(boolean valueForm, Op body, Op handler) {
    super(valueForm);
    this.body = body;
    this.handler = handler;
  }

  /** Each part in its run form: a value-form body's value goes downstream through the same wrapping sink. */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    FilterException caught;
    try {
      if (!body.valueForm) {
        return body.run(state, slots, in, v -> downstream(out, v));
      }
      Object v = body.eval(state, slots, in);
      return v == CallForm.NONE || downstream(out, v);
    } catch (FilterException e) {
      if (e.depth() > 0) {
        e.unwrap();
        throw e;
      }
      caught = e;
    }
    if (!handler.valueForm) {
      return handler.run(state, slots, caught.value(), out);
    }
    Object h = handler.eval(state, slots, caught.value());
    return h == CallForm.NONE || out.emit(h);
  }

  /**
   * The body's value, or the handler's on the value of an error that left the body: the catch is around the body only,
   * and what runs downstream runs after this returned, outside it. Both parts of a value-form Try are value form: a
   * push part would be many or break out, and so would the Try (ADR-0007 #112, #115).
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    FilterException caught;
    try {
      return body.eval(state, slots, in);
    } catch (FilterException e) {
      caught = e;
    }
    return handler.eval(state, slots, caught.value());
  }

  /** Emits a result of the body; an error of what runs downstream of it leaves wrapped once more. */
  private static boolean downstream(Sink out, Object v) {
    try {
      return out.emit(v);
    } catch (FilterException e) {
      e.wrap();
      throw e;
    }
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Body form = new Body(under, slots, out);
    t.start(body, form, slots, in, form);
  }

  /**
   * The heap form while the body runs, and the body's sink: each result goes on above a {@link Downstream} of its own.
   * An error that reaches it of depth above 0 is unwrapped and passes on; one of depth 0, the body's own, ends the body
   * and starts the handler on its value in the Try's place.
   */
  private final class Body extends HeapForm implements HeapSink {
    private final Object[] slots;
    private final HeapSink out;

    Body(HeapForm under, Object[] slots, HeapSink out) {
      super(under);
      this.slots = slots;
      this.out = out;
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object v) {
      t.emit(out, new Downstream(producer), v);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.answer(under, more);
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      if (e.depth() > 0) {
        e.unwrap();
        t.raise(under, e);
        return;
      }
      t.start(handler, under, slots, e.value(), out);
    }
  }

  /**
   * The heap form between one result of the body and what runs downstream of it: the answer passes to the form that
   * produced the result, and an error raised downstream passes there wrapped once more.
   */
  private static final class Downstream extends HeapForm {
    Downstream(HeapForm producer) {
      super(producer);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.answer(under, more);
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      e.wrap();
      t.raise(under, e);
    }
  }
}
