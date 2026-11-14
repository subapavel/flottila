package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code value as p1 ?// p2 ... | body} (ADR-0038, ADR-0008 and its F-32 amendment): each result of the value sets its
 * slot, then the alternatives run in order on the chain's input. At each result of an alternative's match, each group
 * slot is set from that alternative's own slot, or null for a from entry of -1, and the body runs on the chain's input.
 * A FilterException from the match, the body or downstream starts the next alternative, after one step, while one
 * remains; the last alternative runs outside any catch, so its error propagates, and so does an error from the value,
 * which runs outside the catch too. A stop returns false at once, and a Termination is no FilterException.
 */
public final class AltBindOp extends Op {
  private final Op value;
  private final int slot;
  private final Op[] matches;

  /** For each alternative, the slot that sets each group slot, or -1 for null. */
  private final int[][] from;

  private final int[] groups;
  private final Op body;

  public AltBindOp(Op value, int slot, Op[] matches, int[][] from, int[] groups, Op body) {
    this.value = value;
    this.slot = slot;
    this.matches = matches;
    this.from = from;
    this.groups = groups;
    this.body = body;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return value.run(
        state,
        slots,
        in,
        v -> {
          slots[slot] = v;
          return alternatives(state, slots, in, out);
        });
  }

  /** The alternatives on the value now in the slot: each later one after an error of the one before it. */
  private boolean alternatives(RunState state, Object[] slots, Object in, Sink out) {
    int last = matches.length - 1;
    for (int k = 0; k < last; k++) {
      try {
        return alternative(k, state, slots, in, out);
      } catch (FilterException _) {
        // ADR-0038: the next alternative starts, and entering it is a step.
        state.tick();
      }
    }
    return alternative(last, state, slots, in, out);
  }

  /** Alternative {@code k}: at each result of its match, the group slots from its own slots, then the body. */
  private boolean alternative(int k, RunState state, Object[] slots, Object in, Sink out) {
    int[] own = from[k];
    return matches[k].run(
        state,
        slots,
        in,
        m -> {
          for (int g = 0; g < groups.length; g++) {
            slots[groups[g]] = own[g] < 0 ? null : slots[own[g]];
          }
          return body.run(state, slots, in, out);
        });
  }
}
