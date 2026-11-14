package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A constant: its one result is its value, whatever the input, and the sink's answer is the run's. It is no step site
 * (ADR-0008), so it charges no tick (ADR-0031).
 */
public final class ConstOp extends Op {
  private final Object value;

  public ConstOp(Object value) {
    this.value = value;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(value);
  }
}
