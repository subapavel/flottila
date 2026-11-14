package io.github.subapavel.jqj.query.internal.interpret;

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

  public TryOp(Op body, Op handler) {
    this.body = body;
    this.handler = handler;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    FilterException caught;
    try {
      return body.run(state, slots, in, v -> downstream(out, v));
    } catch (FilterException e) {
      if (e.depth() > 0) {
        e.unwrap();
        throw e;
      }
      caught = e;
    }
    return handler.run(state, slots, caught.value(), out);
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
}
