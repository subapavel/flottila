package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** The identity: its one result is its input, and the sink's answer is the run's. */
public final class IdentityOp extends Op {
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(in);
  }
}
