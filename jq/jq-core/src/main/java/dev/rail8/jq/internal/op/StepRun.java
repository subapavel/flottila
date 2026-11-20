package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import java.util.List;

/**
 * A long run of postfix steps of one result or none, {@code .a.b[0][1:2]...}: field reads and indexes and slices with
 * constant keys, as one step that calls them in turn by a loop. A Pipe of them runs one Java frame deeper per step, and
 * a chain past jq's own bytecode limit, which jq rejects and this engine runs, does not fit in a 512 KiB stack that
 * way. The results, errors and charges are the steps' own: a step that has no result ends the run with none, a step
 * that raises raises, and the run adds no tick of its own. The loop runs once per step of the filter's text, never per
 * input value. In a path variant the run is fused still: each step, a {@link PathStep} of no arguments, adds its own
 * key in turn, and an input not found in the variant's input raises the first step's error.
 */
public final class StepRun implements Fn0, PathStep {
  private final Fn0[] steps;

  /** The run of {@code steps}, each a {@link PathStep} that reads no argument. */
  public StepRun(List<Fn0> steps) {
    this.steps = steps.toArray(Fn0[]::new);
  }

  @Override
  public Object apply(RunState state, Object in) {
    Object v = in;
    for (Fn0 step : steps) {
      v = step.apply(state, v);
      if (v == CallForm.NONE) {
        return v;
      }
    }
    return v;
  }

  @Override
  public FilterException untracked(RunState state, Object in, Object key, Object end) {
    return ((PathStep) steps[0]).untracked(state, in, null, null);
  }

  /** {@code at} with each step's key added in order; no step reads {@code in}. */
  @Override
  public Path extend(RunState state, Path at, Object in, Object key, Object end) {
    Path p = at;
    for (Fn0 step : steps) {
      p = ((PathStep) step).extend(state, p, in, null, null);
    }
    return p;
  }
}
