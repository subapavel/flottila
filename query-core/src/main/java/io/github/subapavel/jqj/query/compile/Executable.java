package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.Trampoline;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Termination;

/**
 * A compiled program (GLOSSARY "Executable"): immutable, and it keeps nothing from one run to the next. Each run gets its
 * own frames (ADR-0034, ADR-0045 #107), so two runs on two threads share nothing.
 */
public final class Executable {
  private static final Object[] NO_PARAMETERS = {};

  private final Op root;

  /** The number of slots a run needs, the parameter slots included. */
  private final int slots;

  /** The number of parameter slots, slots {@code 0 .. parameters - 1} (ADR-0038). */
  private final int parameters;

  /** The program's frames, {@code D_f} among them (ADR-0046 #111 amendment). */
  private final Nesting nesting;

  Executable(Op root, int slots, int parameters, Nesting nesting) {
    this.root = root;
    this.slots = slots;
    this.parameters = parameters;
    this.nesting = nesting;
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
   * false when out stopped. The run derives its unit limit {@code U_f} from {@link RunState#stackBytes()} at its start
   * (ADR-0046 and its #111 amendment, {@link StackUnits}): at {@code U_f} 0 or below, which a declared stack of 0
   * gives, this frame is the starter and main and every body run on the trampoline; otherwise main runs in push form
   * and a call whose units reach {@code U_f} starts the trampoline. A run that exhausts its thread's stack ends with
   * {@link Termination#stack()}: the only place that catches a StackOverflowError (ADR-0045 #107), a backstop.
   *
   * @throws IllegalArgumentException when {@code parameters} has not one value per parameter slot; nothing runs
   * @throws Termination of cause STACK when the stack ran out
   */
  public boolean run(RunState state, Object[] parameters, Object in, Sink out) {
    if (parameters.length != this.parameters) {
      throw new IllegalArgumentException("the tree has " + this.parameters + " parameter slots; the run gave " + parameters.length + " values");
    }
    Object[] run = new Object[slots];
    System.arraycopy(parameters, 0, run, 0, parameters.length);
    long unitLimit = StackUnits.unitLimit(state.stackBytes(), nesting.callFreeDepth());
    state.limitUnits(unitLimit);
    try {
      if (unitLimit <= 0) {
        return Trampoline.run(state, root, run, in, out);
      }
      return root.run(state, run, in, out);
    } catch (StackOverflowError _) {
      throw Termination.stack();
    }
  }

  /** The program's frames: each call's units, each body's leaf-emit depth, {@code D_f} and {@code T} (ADR-0046 #111). */
  public Nesting nesting() {
    return nesting;
  }
}
