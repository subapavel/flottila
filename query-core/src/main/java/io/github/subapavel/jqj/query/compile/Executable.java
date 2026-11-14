package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A compiled tree (GLOSSARY "Executable"): immutable, and it keeps nothing from one run to the next. Each run gets its
 * own slots (ADR-0034), so two runs on two threads share nothing.
 */
public final class Executable {
  private static final Object[] NO_PARAMETERS = {};

  private final Op root;

  /** The number of slots a run needs, the parameter slots included. */
  private final int slots;

  /** The number of parameter slots, slots {@code 0 .. parameters - 1} (ADR-0038). */
  private final int parameters;

  Executable(Op root, int slots, int parameters) {
    this.root = root;
    this.slots = slots;
    this.parameters = parameters;
  }

  /**
   * {@link #run(RunState, Object[], Object, Sink)} with no parameter.
   *
   * @throws IllegalArgumentException when the tree has a parameter slot
   */
  public boolean run(RunState state, Object in, Sink out) {
    return run(state, NO_PARAMETERS, in, out);
  }

  /**
   * Pushes the results for {@code in} into {@code out} (ADR-0008), in the run {@code state} belongs to (ADR-0030), with
   * {@code parameters} in the parameter slots, one value per slot in their order (ADR-0038): true when they ran out,
   * false when out stopped.
   *
   * @throws IllegalArgumentException when {@code parameters} has not one value per parameter slot; nothing runs
   */
  public boolean run(RunState state, Object[] parameters, Object in, Sink out) {
    if (parameters.length != this.parameters) {
      throw new IllegalArgumentException("the tree has " + this.parameters + " parameter slots; the run gave " + parameters.length + " values");
    }
    Object[] run = new Object[slots];
    System.arraycopy(parameters, 0, run, 0, parameters.length);
    return root.run(state, run, in, out);
  }
}
