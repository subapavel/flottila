package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.List;

/**
 * A long run of postfix steps of one result or none, {@code .a.b[0][1:2]...}: field reads and indexes and slices with
 * constant keys, as one step that calls them in turn by a loop (F-42, F-44). A Pipe of them runs one Java frame deeper
 * per step, which a chain past jq's own bytecode limit does not fit in a 512 KiB stack (DEVIATIONS "Long path
 * chains"). The results, errors and charges are the steps' own: a step that has no result ends the run with none, a
 * step that raises raises, and the run adds no tick of its own. The loop runs once per step of the filter's text, never
 * per input value.
 */
public final class StepRun implements Fn0 {
  private final Fn0[] steps;

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
}
