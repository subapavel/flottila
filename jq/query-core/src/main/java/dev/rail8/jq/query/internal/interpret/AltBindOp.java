package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code value as p1 ?// p2 ... | body}: each result of the value sets its slot, then the alternatives run in order
 * on the chain's input. At each result of an alternative's match, each group slot is set from that alternative's own
 * slot, or null for a from entry of -1 (a constant null argument), and the body runs on the chain's input.
 * A FilterException from the match, the body or downstream starts the next alternative, after one step, while one
 * remains; the last alternative runs outside any catch, so its error propagates, and so does an error from the value,
 * which runs outside the catch too. A stop returns false at once, and a Termination is no FilterException.
 *
 * <p>In a path variant each match and the body run at the chain's own path, and the value runs in value mode or, when
 * it is tracked, in path mode, its paths dropped.
 */
public final class AltBindOp extends Op {
  private final Op value;

  /** Whether the value runs in path mode in a path variant. */
  private final boolean tracked;

  private final int slot;
  private final Op[] matches;

  /** For each alternative, what sets each group slot: its slot's value, or a constant null. */
  private final Argument[][] from;

  private final int[] groups;
  private final Op body;

  /** A chain whose value runs in value mode in a path variant too. */
  public AltBindOp(Op value, int slot, Op[] matches, Argument[][] from, int[] groups, Op body) {
    this(value, false, slot, matches, from, groups, body);
  }

  /** A chain whose value runs in path mode in a path variant when {@code tracked}. */
  public AltBindOp(Op value, boolean tracked, int slot, Op[] matches, Argument[][] from, int[] groups, Op body) {
    super(false);
    this.value = value;
    this.tracked = tracked;
    this.slot = slot;
    this.matches = matches;
    this.from = from;
    this.groups = groups;
    this.body = body;
  }

  /** Each part in its run form: a value-form part's value runs what comes after it once it returned. */
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
        // The next alternative starts, and entering it is a step.
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

  /** Never called: an AltBind is never eligible for the value form. */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    throw new IllegalStateException("An AltBind has no value form");
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    if (tracked) {
      return value.runPath(
          state,
          slots,
          in,
          at,
          (v, p) -> {
            slots[slot] = v;
            return alternativesPath(state, slots, in, at, out);
          });
    }
    if (value.valueForm) {
      Object v = value.eval(state, slots, in);
      if (v == CallForm.NONE) {
        return true;
      }
      slots[slot] = v;
      return alternativesPath(state, slots, in, at, out);
    }
    return value.run(
        state,
        slots,
        in,
        v -> {
          slots[slot] = v;
          return alternativesPath(state, slots, in, at, out);
        });
  }

  /** {@link #alternatives} in the path variant: each match and the body at {@code at}. */
  private boolean alternativesPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    int last = matches.length - 1;
    for (int k = 0; k < last; k++) {
      try {
        return alternativePath(k, state, slots, in, at, out);
      } catch (FilterException _) {
        // The next alternative starts, and entering it is a step.
        state.tick();
      }
    }
    return alternativePath(last, state, slots, in, at, out);
  }

  /** {@link #alternative} in the path variant: the match's paths are dropped, and the body runs at {@code at}. */
  private boolean alternativePath(int k, RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Argument[] own = from[k];
    return matches[k].runPath(
        state,
        slots,
        in,
        at,
        (m, p) -> {
          groups(own, slots, in);
          return body.runPath(state, slots, in, at, out);
        });
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
    HeapForm form = k < matches.length - 1 ? new Alternatives(producer, k, slots, in, out, null, null) : producer;
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

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    if (tracked) {
      t.startPath(
          value,
          under,
          slots,
          in,
          at,
          (d, producer, v, p) -> {
            slots[slot] = v;
            alternativePath(0, d, producer, slots, in, at, out);
          });
      return;
    }
    t.start(
        value,
        under,
        slots,
        in,
        (d, producer, v) -> {
          slots[slot] = v;
          alternativePath(0, d, producer, slots, in, at, out);
        });
  }

  /** {@link #alternative}'s heap form in the path variant: each match and the body at {@code at}. */
  private void alternativePath(int k, Trampoline t, HeapForm producer, Object[] slots, Object in, Path at, HeapPathSink out) {
    HeapForm form = k < matches.length - 1 ? new Alternatives(producer, k, slots, in, null, at, out) : producer;
    Argument[] own = from[k];
    t.startPath(
        matches[k],
        form,
        slots,
        in,
        at,
        (d, match, m, p) -> {
          groups(own, slots, in);
          d.startPath(body, match, slots, in, at, out);
        });
  }

  /**
   * The heap form of alternative {@code k} while one remains after it: an error from its match, the body or downstream
   * starts the next alternative after one step, and its run's answer is the value's result's. In a path variant the
   * next alternative runs at {@code at} into {@code paths}.
   */
  private final class Alternatives extends HeapForm {
    private final int k;
    private final Object[] slots;
    private final Object in;
    private final HeapSink out;
    private final Path at;
    private final HeapPathSink paths;

    Alternatives(HeapForm under, int k, Object[] slots, Object in, HeapSink out, Path at, HeapPathSink paths) {
      super(under);
      this.k = k;
      this.slots = slots;
      this.in = in;
      this.out = out;
      this.at = at;
      this.paths = paths;
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.answer(under, more);
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      t.state.tick();
      if (paths != null) {
        alternativePath(k + 1, t, under, slots, in, at, paths);
      } else {
        alternative(k + 1, t, under, slots, in, out);
      }
    }
  }
}
