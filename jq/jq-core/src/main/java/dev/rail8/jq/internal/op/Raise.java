package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;

/**
 * jq's {@code error/0}: raises its input as the error value, whatever its kind, null included, as jq 1.8.2 does. It
 * builds nothing and charges nothing. {@code error(f)} is {@code f | error}, as builtin.jq defines it.
 */
public final class Raise implements Fn0 {
  /** {@code error}. */
  public static final Operation OP = new Operation("error", new Raise(), Declared.FRESH);

  private Raise() {}

  @Override
  public Object apply(RunState state, Object in) {
    throw new FilterException(in);
  }
}
