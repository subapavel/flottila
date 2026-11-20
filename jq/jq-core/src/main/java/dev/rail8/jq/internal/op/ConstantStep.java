package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;

/**
 * A step whose key is a constant, {@code .[0]}, {@code .["a"]} or {@code .[1:2]}, as a step of its input alone, so a
 * fused run ({@link StepRun}) can call it: its results, errors and charges are the step's own on that key, and in a
 * path variant it adds the path key the step adds for it, whatever arguments it is passed.
 */
public final class ConstantStep implements Fn0, PathStep {
  private final Fn1 one;
  private final Fn2 two;
  private final PathStep step;
  private final Object key;
  private final Object end;

  private ConstantStep(Fn1 one, Fn2 two, PathStep step, Object key, Object end) {
    this.one = one;
    this.two = two;
    this.step = step;
    this.key = key;
    this.end = end;
  }

  /** The step {@code step}, an {@link Index}, by the constant {@code key}. */
  public static ConstantStep of(Fn1 step, Object key) {
    return new ConstantStep(step, null, (PathStep) step, key, null);
  }

  /** The step {@code step}, a {@link Slice}, from the constant {@code key} to the constant {@code end}. */
  public static ConstantStep of(Fn2 step, Object key, Object end) {
    return new ConstantStep(null, step, (PathStep) step, key, end);
  }

  @Override
  public Object apply(RunState state, Object in) {
    return one != null ? one.apply(state, in, key) : two.apply(state, in, key, end);
  }

  @Override
  public FilterException untracked(RunState state, Object in, Object ignoredKey, Object ignoredEnd) {
    return step.untracked(state, in, key, end);
  }

  @Override
  public Path extend(RunState state, Path at, Object in, Object ignoredKey, Object ignoredEnd) {
    return step.extend(state, at, in, key, end);
  }
}
