package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code try body catch handler}: the body's results pass through a sink that wraps each error thrown downstream of
 * them, so when an error leaves the body, one of depth above 0 is unwrapped and raised again, and one of depth 0, the
 * body's own, ends the body and runs the handler on its value. The handler runs outside the catch,
 * so its errors propagate. A break or a stop is a false, which passes at once; a Termination is no FilterException.
 * Entering the handler charges no step. In a path variant the body runs at the Try's own path and the handler on the
 * error's value, which was not found in the input, untracked.
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
   * push part would be many or break out, and so would the Try.
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
    Body form = new Body(under, slots, out, null);
    t.start(body, form, slots, in, form);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    FilterException caught;
    try {
      return body.runPath(state, slots, in, at, (v, p) -> downstream(out, v, p));
    } catch (FilterException e) {
      if (e.depth() > 0) {
        e.unwrap();
        throw e;
      }
      caught = e;
    }
    return handler.runPath(state, slots, caught.value(), null, out);
  }

  /** {@link #downstream(Sink, Object)} in a path variant: the result goes on with its path {@code p}. */
  private static boolean downstream(PathSink out, Object v, Path p) {
    try {
      return out.emit(v, p);
    } catch (FilterException e) {
      e.wrap();
      throw e;
    }
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Body form = new Body(under, slots, null, out);
    t.startPath(body, form, slots, in, at, form);
  }

  /**
   * The heap form while the body runs, and the body's sink: each result goes on above a {@link Downstream} of its own.
   * An error that reaches it of depth above 0 is unwrapped and passes on; one of depth 0, the body's own, ends the body
   * and starts the handler on its value in the Try's place. In a path variant the results go on into {@code paths}
   * with their paths, and the handler runs untracked.
   */
  private final class Body extends HeapForm implements HeapSink, HeapPathSink {
    private final Object[] slots;
    private final HeapSink out;
    private final HeapPathSink paths;

    Body(HeapForm under, Object[] slots, HeapSink out, HeapPathSink paths) {
      super(under);
      this.slots = slots;
      this.out = out;
      this.paths = paths;
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object v) {
      t.emit(out, new Downstream(producer), v);
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object v, Path p) {
      t.emitPath(paths, new Downstream(producer), v, p);
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
      if (paths != null) {
        t.startPath(handler, under, slots, e.value(), null, paths);
      } else {
        t.start(handler, under, slots, e.value(), out);
      }
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
