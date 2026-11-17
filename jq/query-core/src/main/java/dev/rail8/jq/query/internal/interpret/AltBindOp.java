package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code value as p1 ?// p2 ... | body} (ADR-0038, ADR-0008 and its F-32 amendment): each result of the value sets its
 * slot, then the alternatives run in order on the chain's input. At each result of an alternative's match, each group
 * slot is set from that alternative's own slot, or null for a from entry of -1 (a constant null argument), and the body
 * runs on the chain's input.
 * A FilterException from the match, the body or downstream starts the next alternative, after one step, while one
 * remains; the last alternative runs outside any catch, so its error propagates, and so does an error from the value,
 * which runs outside the catch too. A stop returns false at once, and a Termination is no FilterException.
 */
public final class AltBindOp extends Op {
  private final Op value;
  private final int slot;
  private final Op[] matches;

  /** For each alternative, what sets each group slot: its slot's value, or a constant null. */
  private final Argument[][] from;

  private final int[] groups;
  private final Op body;

  public AltBindOp(Op value, int slot, Op[] matches, Argument[][] from, int[] groups, Op body) {
    super(false);
    this.value = value;
    this.slot = slot;
    this.matches = matches;
    this.from = from;
    this.groups = groups;
    this.body = body;
  }

  /** Each part in its run form: a value-form part's value runs what comes after it once it returned (ADR-0047 #116). */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    if (value.valueForm) {
      Object v = value.eval(state, slots, in);
      if (v == CallForm.NONE) {
        return true;
      }
      slots[slot] = v;
      return alternatives(state, slots, in, out);
    }
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
    Argument[] own = from[k];
    Sink rest =
        body.valueForm
            ? m -> {
              groups(own, slots, in);
              Object w = body.eval(state, slots, in);
              return w == CallForm.NONE || out.emit(w);
            }
            : m -> {
              groups(own, slots, in);
              return body.run(state, slots, in, out);
            };
    if (!matches[k].valueForm) {
      return matches[k].run(state, slots, in, rest);
    }
    Object m = matches[k].eval(state, slots, in);
    return m == CallForm.NONE || rest.emit(m);
  }

  /** Each group slot from the alternative's own entry in {@code own}. */
  private void groups(Argument[] own, Object[] slots, Object in) {
    for (int g = 0; g < groups.length; g++) {
      slots[groups[g]] = own[g].read(slots, in);
    }
  }

  /** Never called: an AltBind is never eligible for the value form (ADR-0047 #116). */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    throw new IllegalStateException("An AltBind has no value form");
  }

  /**
   * The value runs under the AltBind's own form, so its errors pass no catch; each of its results sets the slot and
   * starts the first alternative above the form that produced it.
   */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(
        value,
        under,
        slots,
        in,
        (d, producer, v) -> {
          slots[slot] = v;
          alternative(0, d, producer, slots, in, out);
        });
  }

  /**
   * Alternative {@code k} on the value in the slot, above {@code producer}: each but the last under its own
   * {@link Alternatives}, which catches; the last right on {@code producer}, outside any catch. At each result of its
   * match, the group slots are set and the body starts above the form that produced it.
   */
  private void alternative(int k, Trampoline t, HeapForm producer, Object[] slots, Object in, HeapSink out) {
    HeapForm form = k < matches.length - 1 ? new Alternatives(producer, k, slots, in, out) : producer;
    Argument[] own = from[k];
    t.start(
        matches[k],
        form,
        slots,
        in,
        (d, match, m) -> {
          groups(own, slots, in);
          d.start(body, match, slots, in, out);
        });
  }

  /**
   * The heap form of alternative {@code k} while one remains after it: an error from its match, the body or downstream
   * starts the next alternative after one step (ADR-0038, F-32), and its run's answer is the value's result's.
   */
  private final class Alternatives extends HeapForm {
    private final int k;
    private final Object[] slots;
    private final Object in;
    private final HeapSink out;

    Alternatives(HeapForm under, int k, Object[] slots, Object in, HeapSink out) {
      super(under);
      this.k = k;
      this.slots = slots;
      this.in = in;
      this.out = out;
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.answer(under, more);
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      t.state.tick();
      alternative(k + 1, t, under, slots, in, out);
    }
  }
}
