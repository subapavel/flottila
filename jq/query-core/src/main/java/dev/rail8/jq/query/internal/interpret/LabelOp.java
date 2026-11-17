package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code label $name | body} (ADR-0008, ADR-0039): each run takes a fresh token into its slot and runs the body. A false
 * from the body that is a Break to this token ends the body as if it ran out; any other false, a stop or a Break to an
 * outer Label, passes on. No step of its own.
 */
public final class LabelOp extends Op {
  private final int slot;
  private final Op body;

  public LabelOp(boolean valueForm, int slot, Op body) {
    super(valueForm);
    this.slot = slot;
    this.body = body;
  }

  /**
   * The body in push form: a push-form Label's body is never value form, since a body that is at most one and may not
   * break out makes its Label so (ADR-0007 #112, #115).
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    long token = state.label();
    slots[slot] = token;
    return body.run(state, slots, in, out) || state.caught(token);
  }

  /**
   * The body's value. A push body of a value-form Label breaks only to it: it runs into a {@link Holder}, and a Break to
   * the token, which this clears, is no result, as when the body ran out.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    long token = state.label();
    slots[slot] = token;
    if (body.valueForm) {
      return body.eval(state, slots, in);
    }
    Holder holder = new Holder();
    // The holder answers its one result with a stop, so a false is that result unless it is a Break to the token.
    if (body.run(state, slots, in, holder) || state.caught(token)) {
      return CallForm.NONE;
    }
    return holder.take();
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
