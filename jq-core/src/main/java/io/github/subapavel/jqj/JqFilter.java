package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.explain.Explain;
import io.github.subapavel.jqj.internal.parse.Resolver;
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

  /** The name of each function of the program the executable was compiled from, by its place. */
  private final List<String> names;

  JqFilter(Executable executable, Resolver.Resolved resolved, Limits limits, List<String> variables) {
    this.executable = executable;
    this.names = resolved.names();
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
   * @throws JqTermination with exit code 5 when the steps, the deadline, the recursion depth, the bytes built or written
   *     run out, the thread is interrupted, or the thread's stack runs out (cause STACK, "Stack exhausted")
   * @throws IllegalArgumentException when {@code bindings} leave a declared variable unbound; nothing runs
   * @throws IllegalStateException when {@code workspace} is running another evaluation, which goes on unchanged
   */
  public boolean run(JqValue input, Bindings bindings, JqSink sink, Workspace workspace) {
    Object[] parameters = parameters(bindings);
    RunState state = workspace.state;
    try {
      // Outside the inner try: a refused run never ends the one running, and a run ended here began none.
      state.begin(limits.steps(), limits.deadline().toNanos(), limits.bytesBuilt(), limits.bytesWritten(), limits.recursionDepth(), limits.stackBytes());
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

  /**
   * The IR this filter runs and its facts (ADR-0016, ADR-0018), the text {@code jqj --explain} prints: unstable and
   * outside semver. Main, then each function of the frozen program, the specialised one in the optimised compile
   * (ADR-0045 #117), one line per node: its function ({@code main} or
   * {@code fn<place>}), its pre-order id in that body from 0, its kind and its source span {@code <start>..<end>} in
   * UTF-8 bytes, then its facts as {@code name=value}. A Call shows its kind, {@code
   * call=<PLAIN|RECURSIVE|TAIL_SELF|TAIL_GROUP>}, no tail kind in the conservative compile (ADR-0047 #118), and {@code
   * callee=fn<place>}, a ParamCall {@code param=<j>}, and every node its cardinality {@code
   * card=<NONE|ONE|AT_MOST_ONE|MANY>}, MANY everywhere in the conservative compile, then whether it may break out,
   * {@code breaks=<true|false>}, true everywhere in the conservative compile, then its run form, {@code
   * form=<push|value>}, value only where its cardinality is at most one and it may not break out. Each function's
   * lines follow a line {@code function fn<place> <name>/<arity> <start>..<end> closures=<n> values=<m>}, and a copy's
   * with {@code
   * copyOf=fn<o>}, the original it copies, whose name it has. The stack units (ADR-0046) follow: a Call's and a
   * ParamCall's {@code units}, the {@code emitDepth} of each function and closure argument, and a closing line with
   * {@code callFreeDepth}, {@code starterUnits}, {@code bytesPerUnit} and {@code reserve}, then {@code specialised=<n>},
   * the nodes the copies hold, and {@code refused=<k>}, the keys that did not fit the specialisation cap. Every line
   * ends with an LF.
   */
  public String explain() {
    return Explain.of(executable, names);
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

  /**
   * The termination a host sees for query-core's (ADR-0016): the same cause and budget, the budget's message, and the
   * stack's "Stack exhausted" (ADR-0045 #107).
   */
  private static JqTermination terminated(Termination t) {
    return switch (t.cause()) {
      case BUDGET -> new JqTermination(JqTermination.Cause.BUDGET, TERMINATED, JqNull.NULL, budget(t.budget()), t.getMessage());
      case CANCELLED -> new JqTermination(JqTermination.Cause.CANCELLED, TERMINATED, JqNull.NULL, null);
      case STACK -> new JqTermination(JqTermination.Cause.STACK, TERMINATED, JqNull.NULL, null, t.getMessage());
    };
  }

  private static JqTermination.Budget budget(Termination.Budget budget) {
    return switch (budget) {
      case STEPS -> JqTermination.Budget.STEPS;
      case DEADLINE -> JqTermination.Budget.DEADLINE;
      case RECURSION_DEPTH -> JqTermination.Budget.RECURSION_DEPTH;
      case BYTES_BUILT -> JqTermination.Budget.BYTES_BUILT;
      case BYTES_WRITTEN -> JqTermination.Budget.BYTES_WRITTEN;
    };
  }
}
