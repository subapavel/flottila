package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqNull;

/**
 * The loop state as jq sees it: an Advance leaves no value in the state slot, which query-core holds as a Java null
 * since its null is no jq value; jq's own {@code LOADVN} leaves jq's null. So the resolver passes an update's input and
 * a reduce's result through this operation, which gives jq's null for none and any other value as it is, as jq 1.8.2
 * does when a reduce's update gives no result. It ticks and charges nothing.
 */
public final class LoopState implements Fn0 {
  /** The state, jq's null for none. */
  public static final Operation OP = new Operation("_state", new LoopState(), Declared.KEEPS);

  private LoopState() {}

  @Override
  public Object apply(RunState state, Object in) {
    return in == null ? JqNull.NULL : in;
  }
}
