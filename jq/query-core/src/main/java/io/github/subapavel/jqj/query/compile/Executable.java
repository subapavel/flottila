package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.analysis.CallKind;
import io.github.subapavel.jqj.query.analysis.CallKinds;
import io.github.subapavel.jqj.query.analysis.Facts;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.Trampoline;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Termination;

/**
 * A compiled program (GLOSSARY "Executable"): immutable, and it keeps nothing from one run to the next. Each run gets its
 * own frames (ADR-0034, ADR-0045 #107), so two runs on two threads share nothing. Its facts travel with it (ADR-0045
 * #112).
 */
public final class Executable {
  private static final Object[] NO_PARAMETERS = {};

  private final Op root;

  /** The number of slots a run needs, the parameter slots included. */
  private final int slots;

  /** The number of parameter slots, slots {@code 0 .. parameters - 1} (ADR-0038). */
  private final int parameters;

  /** The facts of the program this was compiled from, by its freeze ids. */
  private final Facts facts;

  /** The kind of each Call of the program this was compiled from, by its freeze ids (ADR-0047 #118). */
  private final CallKinds kinds;

  /** The program's frames, {@code D_f} among them (ADR-0046 #111 amendment). */
  private final Nesting nesting;

  /** The frozen program this runs (ADR-0045 #117). */
  private final Frozen frozen;

  /** Whether the main tree's root runs by its value form (ADR-0047 #116), so that the run entry emits its value. */
  private final boolean value;

  Executable(Op root, int slots, int parameters, Facts facts, CallKinds kinds, Nesting nesting, Frozen frozen) {
    this.root = root;
    this.slots = slots;
    this.parameters = parameters;
    this.facts = facts;
    this.kinds = kinds;
    this.nesting = nesting;
    this.frozen = frozen;
    this.value = form(-1, 0) == RunForm.VALUE;
  }

  /** The facts of the frozen program, by body and freeze id ({@link Frozen#numbering()}). */
  public Facts facts() {
    return facts;
  }

  /**
   * The program this runs, as freeze left it: the specialised program in the optimised compile, its numbering, groups
   * and origins, and the specialisation counts (ADR-0045 #117).
   */
  public Frozen frozen() {
    return frozen;
  }

  /**
   * The run form of node {@code id}, a freeze id ({@code query.analysis.Numbering}), of the body {@code function}, -1
   * for the main tree (ADR-0047 #116): VALUE where its cardinality is at most one and it may not break out, which the
   * conservative compile never has.
   */
  public RunForm form(int function, int id) {
    return RunForm.of(facts, function, id);
  }

  /**
   * The kind of the Call {@code id}, a freeze id, of the body {@code function}, -1 for the main tree (ADR-0047 #118):
   * PLAIN from main or into another recursive group, RECURSIVE into the caller's group, TAIL_SELF or TAIL_GROUP for a
   * tail call, which the conservative compile never has; null for a node that is no Call.
   */
  public CallKind callKind(int function, int id) {
    return kinds.kind(function, id);
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
   * gives, this frame is the starter and main and every body run on the trampoline; otherwise main runs in its run form,
   * a value-form root's value emitted here (ADR-0047 #116), and a call whose units reach {@code U_f} starts the
   * trampoline. A run that exhausts its thread's stack ends with
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
      if (!value) {
        return root.run(state, run, in, out);
      }
      Object v = root.eval(state, run, in);
      return v == CallForm.NONE || out.emit(v);
    } catch (StackOverflowError _) {
      throw Termination.stack();
    }
  }

  /** The program's frames: each call's units, each body's leaf-emit depth, {@code D_f} and {@code T} (ADR-0046 #111). */
  public Nesting nesting() {
    return nesting;
  }
}
