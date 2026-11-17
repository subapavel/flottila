package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNull;

/**
 * The loop state as jq sees it (ADR-0041, GLOSSARY "Loop state"): an Advance leaves no value in the state slot, which
 * query-core holds as a Java null since its null is no jq value (founding §14); jq's own {@code LOADVN} leaves jq's
 * null. So the resolver passes an update's input and a reduce's result through this operation, which gives jq's null
 * for none and any other value as it is (jq-ref 1.8.2, recorded: diff/reduce.rec reduce/update-empty gives null,
 * reduce/update-empty-then-next [null,3]). It ticks and charges nothing.
 */
public final class LoopState implements Fn0 {
  /** The state, jq's null for none. */
  public static final Operation OP = new Operation("_state", new LoopState());

  private LoopState() {}

  @Override
  public Object apply(RunState state, Object in) {
    return in == null ? JqNull.NULL : in;
  }
}
