package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code label $name | body} (ADR-0008, ADR-0039): each run takes a fresh token into its slot and runs the body. A false
 * from the body that is a Break to this token ends the body as if it ran out; any other false, a stop or a Break to an
 * outer Label, passes on. No step of its own.
 */
public final class LabelOp extends Op {
  private final int slot;
  private final Op body;

  public LabelOp(int slot, Op body) {
    this.slot = slot;
    this.body = body;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    long token = state.label();
    slots[slot] = token;
    return body.run(state, slots, in, out) || state.caught(token);
  }
}
