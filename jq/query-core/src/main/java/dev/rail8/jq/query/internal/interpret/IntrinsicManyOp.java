package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A call of a {@link Many} as a loop over its cursor: one step per result, before its emit; a stop ends the loop at
 * once, with no further result or step. Each loop gets its arguments and the Many's loop slots in one new
 * array. Its heap form is the {@link Loop}, which keeps the cursor while each result is answered. In a path variant
 * each result's path is made by its operation's path rule; a step raises on an untracked input before the loop's first
 * call and extends the input's path by each cursor's key.
 */
public final class IntrinsicManyOp extends Op {
  /** The array of every loop with no argument and no loop slot: it has nothing to write, so the loops share it. */
  private static final Object[] NO_ARGS = {};

  private final Many many;
  private final Argument[] args;

  /** The arguments and the loop slots. */
  private final int width;

  private final ResultPath rule;

  /** The form's path side, for a step; null for any other rule. */
  private final PathStep step;

  /** A call whose results are untracked in a path variant. */
  public IntrinsicManyOp(Many many, Argument[] args) {
    this(many, args, ResultPath.UNTRACKED);
  }

  /** A call whose results' paths are made by {@code rule}, UNTRACKED, SAME or STEP; a STEP needs a {@link PathStep}. */
  public IntrinsicManyOp(Many many, Argument[] args, ResultPath rule) {
    super(false);
    this.many = many;
    this.args = args;
    this.width = args.length + many.loopSlots();
    this.rule = rule;
    this.step = rule.steps() ? (PathStep) many : null;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] values = values(slots, in);
    for (long at = many.first(state, in, values); at != Many.END; at = many.next(state, in, values, at)) {
      state.tick();
      if (!out.emit(many.value(state, in, values, at))) {
        return false;
      }
    }
    return true;
  }

  /** Never called: a Many intrinsic is never eligible for the value form. */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    throw new IllegalStateException("A Many intrinsic has no value form");
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Object[] values = values(slots, in);
    new Loop(under, in, values, out, null, null).at(t, many.first(t.state, in, values));
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    Object[] values = values(slots, in);
    rule.check(step, state, at, in, null, null);
    for (long c = many.first(state, in, values); c != Many.END; c = many.next(state, in, values, c)) {
      state.tick();
      Object v = many.value(state, in, values, c);
      if (!out.emit(v, path(state, at, in, values, c))) {
        return false;
      }
    }
    return true;
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    Object[] values = values(slots, in);
    rule.check(step, t.state, at, in, null, null);
    new Loop(under, in, values, null, at, out).at(t, many.first(t.state, in, values));
  }

  /** The path of the result at {@code cursor} of the loop on {@code in} at {@code at}: a step's adds the cursor's key. */
  private Path path(RunState state, Path at, Object in, Object[] values, long cursor) {
    Object key = step == null ? null : many.key(state, in, values, cursor);
    return rule.of(step, state, at, in, key, null);
  }

  /** The loop's array: its arguments' values for {@code in}, then room for the loop slots. */
  private Object[] values(Object[] slots, Object in) {
    Object[] values = NO_ARGS;
    if (width != 0) {
      values = new Object[width];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].read(slots, in);
      }
    }
    return values;
  }

  /**
   * The heap form of one loop: its input, its array and the cursor of the result it emitted last. In a path variant
   * its input is at {@code path} and each result goes into {@code paths} with its own.
   */
  private final class Loop extends HeapForm {
    private final Object in;
    private final Object[] values;
    private final HeapSink out;
    private final Path path;
    private final HeapPathSink paths;
    private long at;

    Loop(HeapForm under, Object in, Object[] values, HeapSink out, Path path, HeapPathSink paths) {
      super(under);
      this.in = in;
      this.values = values;
      this.out = out;
      this.path = path;
      this.paths = paths;
    }

    /** At the cursor {@code at}: past the end the loop runs out, otherwise one step and the result's emit. */
    void at(Trampoline t, long at) {
      if (at == Many.END) {
        t.answer(under, true);
        return;
      }
      this.at = at;
      t.state.tick();
      Object v = many.value(t.state, in, values, at);
      if (paths != null) {
        t.emitPath(paths, this, v, path(t.state, path, in, values, at));
      } else {
        t.emit(out, this, v);
      }
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      at(t, many.next(t.state, in, values, at));
    }
  }
}
