package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code v1 as $s1 | … | vn as $sn | body} (ADR-0043), run as the chain of nested {@link BindOp}s it means. A tail
 * value runs into a {@link Holder}, sets its slot and the loop goes on to the next value; with no result the Binds
 * runs out there. Any other value runs the rest, the later values and the body, inside its emit, as a BindOp does. So
 * the stack grows by one recursion per generator value, not per value. Each value and the body run on the Binds' own
 * input; no step of its own.
 */
public final class BindsOp extends Op {
  private final Op[] values;

  /** Whether each value is a tail emitter: one result at most, and it never breaks out (ADR-0043). */
  private final boolean[] tails;

  /** The slot each value sets. */
  private final int[] targets;

  private final Op body;

  public BindsOp(Op[] values, boolean[] tails, int[] targets, Op body) {
    this.values = values;
    this.tails = tails;
    this.targets = targets;
    this.body = body;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return from(0, state, slots, in, out, new Holder());
  }

  /**
   * The values from {@code first} on, then the body, with the slots of the values before {@code first} set. The one
   * {@code holder} of the run serves every tail value: each is taken, leaving it empty, before the next one runs.
   */
  private boolean from(int first, RunState state, Object[] slots, Object in, Sink out, Holder holder) {
    for (int k = first; k < values.length; k++) {
      int slot = targets[k];
      if (!tails[k]) {
        int next = k + 1;
        return values[k].run(
            state,
            slots,
            in,
            v -> {
              slots[slot] = v;
              return from(next, state, slots, in, out, holder);
            });
      }
      // The holder answers its one result with a stop and a tail emitter never breaks out, so a true means no result:
      // the Binds runs out. A false means the result came, and it sets the slot.
      if (values[k].run(state, slots, in, holder)) {
        return true;
      }
      slots[slot] = holder.take();
    }
    return body.run(state, slots, in, out);
  }
}
