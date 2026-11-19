package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathFormat;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code path(body)}: the body runs by its path variant on the input, from the root path, and for each of its results
 * the op charges one step per key of the result's path, then emits the path as {@code paths} makes it a value. An
 * untracked result raises {@code paths}' invalid-path error when it comes, so a body with no results raises nothing.
 * Its own path variants are the defaults: what it emits is untracked.
 */
public final class PathOfOp extends Op {
  private final Op body;
  private final PathFormat paths;

  public PathOfOp(boolean valueForm, Op body, PathFormat paths) {
    super(valueForm);
    this.body = body;
    this.paths = paths;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return body.runPath(state, slots, in, Path.ROOT, new Values(state, paths, out));
  }

  /**
   * The one path of a value-form PathOf, or {@link CallForm#NONE}: its body is at most one and may not break out, so
   * it runs to its end, as in {@link #run}.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    Last last = new Last(state, paths);
    body.runPath(state, slots, in, Path.ROOT, last);
    return last.value;
  }

  /** The body starts by its path variant under {@code under}, and each result's path goes on to {@code out}. */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.startPath(body, under, slots, in, Path.ROOT, (d, producer, v, p) -> d.emit(out, producer, value(d.state, paths, v, p)));
  }

  /**
   * The value of the path {@code p} of the result {@code v}, one step charged per key first.
   *
   * @throws dev.rail8.jq.query.run.FilterException {@code paths}' invalid-path error when {@code p} is null
   */
  private static Object value(RunState state, PathFormat paths, Object v, Path p) {
    if (p == null) {
      throw paths.invalid(state, v);
    }
    state.tick(p.length());
    return paths.value(state, p);
  }

  /** The body's sink in push form: each result's path goes on as a value. */
  private static final class Values implements PathSink {
    private final RunState state;
    private final PathFormat paths;
    private final Sink out;

    Values(RunState state, PathFormat paths, Sink out) {
      this.state = state;
      this.paths = paths;
      this.out = out;
    }

    @Override
    public boolean emit(Object v, Path p) {
      return out.emit(value(state, paths, v, p));
    }
  }

  /** The body's sink in value form: it keeps the path of the result as a value, and lets the body run on. */
  private static final class Last implements PathSink {
    private final RunState state;
    private final PathFormat paths;
    private Object value = CallForm.NONE;

    Last(RunState state, PathFormat paths) {
      this.state = state;
      this.paths = paths;
    }

    @Override
    public boolean emit(Object v, Path p) {
      value = value(state, paths, v, p);
      return true;
    }
  }
}
