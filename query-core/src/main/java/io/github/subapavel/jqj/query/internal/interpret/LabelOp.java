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

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    long token = t.state.label();
    slots[slot] = token;
    t.start(body, new Body(under, token), slots, in, out);
  }

  /** The heap form while the body runs: a false that is a Break to its token answers as if the body ran out. */
  private static final class Body extends HeapForm {
    private final long token;

    Body(HeapForm under, long token) {
      super(under);
      this.token = token;
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.answer(under, more || t.state.caught(token));
    }
  }
}
