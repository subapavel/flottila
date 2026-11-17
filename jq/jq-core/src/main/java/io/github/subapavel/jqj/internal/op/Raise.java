package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;

/**
 * jq's {@code error/0} (ADR-0039): raises its input as the error value, whatever its kind, null included (jq-ref 1.8.2,
 * recorded: control/error-null-caught, control/error-number). It builds nothing and charges nothing. {@code error(f)} is
 * {@code f | error}, as builtin.jq defines it (ADR-0040).
 */
public final class Raise implements Fn0 {
  /** {@code error}. */
  public static final Operation OP = new Operation("error", new Raise());

  private Raise() {}

  @Override
  public Object apply(RunState state, Object in) {
    throw new FilterException(in);
  }
}
