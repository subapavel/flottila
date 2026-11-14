package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.value.Detach;
import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;

/** A compiled filter, immutable and thread-safe (ADR-0016). It runs in push form (ADR-0008). */
public final class JqFilter {
  /** ADR-0017: a run that a budget or a cancel ended exits with 5. */
  private static final int TERMINATED = 5;

  private final Executable executable;

  /** The engine's limits, each run's budgets until a run brings its own (ADR-0030). */
  private final Limits limits;

  /** The declared host variables, sorted: each name's place is its parameter slot (ADR-0038). */
  private final List<String> variables;

  JqFilter(Executable executable, Limits limits, List<String> variables) {
    this.executable = executable;
    this.limits = limits;
    this.variables = variables;
  }

  /**
   * Runs the filter on {@code input}, each declared variable bound to its value in {@code bindings}; true when the
   * results ran out, false when the sink stopped. The run begins the Workspace's run state with the engine's limits
   * and ends it however it ends, so the Workspace is free again after a termination, an error or the sink's exception
   * (ADR-0030). A name in {@code bindings} that the compile did not declare is ignored.
   *
   * @throws JqFilterException with jq's error value, detached from every document, when the filter raises an error
   * @throws JqTermination with exit code 5 when the steps, the deadline, the bytes built or written run out, or the thread
   *     is interrupted
   * @throws IllegalArgumentException when {@code bindings} leave a declared variable unbound; nothing runs
   * @throws IllegalStateException when {@code workspace} is running another evaluation, which goes on unchanged
   */
  public boolean run(JqValue input, Bindings bindings, JqSink sink, Workspace workspace) {
    Object[] parameters = parameters(bindings);
    RunState state = workspace.state;
    try {
      // Outside the inner try: a refused run never ends the one running, and a run ended here began none.
      state.begin(limits.steps(), limits.deadline().toNanos(), limits.bytesBuilt(), limits.bytesWritten());
      try {
        // query-core hands values over as Object; every value this filter pushes or raises is a jq value.
        return executable.run(state, parameters, input, v -> sink.emit((JqValue) v));
      } finally {
        state.end();
      }
    } catch (FilterException e) {
      // After the run, so the detach charges nothing (ADR-0016's host rule, ADR-0039), and while the document still
      // holds: the payload outlives the Workspace's next read.
      throw new JqFilterException(Detach.of((JqValue) e.value()));
    } catch (Termination t) {
      throw terminated(t);
    }
  }

  /** The value of each declared variable in {@code bindings}, in the order of its parameter slot. */
  private Object[] parameters(Bindings bindings) {
    Object[] values = new Object[variables.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = bindings.value(variables.get(i));
      if (values[i] == null) {
        throw new IllegalArgumentException("the declared variable $" + variables.get(i) + " is not bound");
      }
    }
    return values;
  }

  /** The termination a host sees for query-core's (ADR-0016): the same cause and budget, the budget's message. */
  private static JqTermination terminated(Termination t) {
    return switch (t.cause()) {
      case BUDGET -> new JqTermination(JqTermination.Cause.BUDGET, TERMINATED, JqNull.NULL, budget(t.budget()), t.getMessage());
      case CANCELLED -> new JqTermination(JqTermination.Cause.CANCELLED, TERMINATED, JqNull.NULL, null);
    };
  }

  private static JqTermination.Budget budget(Termination.Budget budget) {
    return switch (budget) {
      case STEPS -> JqTermination.Budget.STEPS;
      case DEADLINE -> JqTermination.Budget.DEADLINE;
      case BYTES_BUILT -> JqTermination.Budget.BYTES_BUILT;
      case BYTES_WRITTEN -> JqTermination.Budget.BYTES_WRITTEN;
    };
  }
}
