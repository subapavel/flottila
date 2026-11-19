package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.analysis.CallKind;
import dev.rail8.jq.query.analysis.CallKinds;
import dev.rail8.jq.query.analysis.Facts;
import dev.rail8.jq.query.internal.interpret.Op;
import dev.rail8.jq.query.internal.interpret.Trampoline;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;
import dev.rail8.jq.query.run.Termination;

/**
 * A compiled program: immutable, and it keeps nothing from one run to the next. Each run gets its own frames, so two
 * runs on two threads share nothing. Its facts travel with it.
 */
public final class Executable {
  private static final Object[] NO_PARAMETERS = {};

  private final Op root;

  /** The number of cells main's frame has: its slots, the parameter slots included, then its hidden cells. */
  private final int slots;

  /** The number of parameter slots, slots {@code 0 .. parameters - 1}. */
  private final int parameters;

  /** The facts of the program this was compiled from, by its freeze ids. */
  private final Facts facts;

  /** The kind of each Call of the program this was compiled from, by its freeze ids. */
  private final CallKinds kinds;

  /** The program's frames, {@code D_f} among them. */
  private final Nesting nesting;

  /** The frozen program this runs. */
  private final Frozen frozen;

  /** Whether the main tree's root runs by its value form, so that the run entry emits its value. */
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
   * and origins, and the specialisation counts.
   */
  public Frozen frozen() {
    return frozen;
  }

  /**
   * The run form of node {@code id}, a freeze id ({@code query.analysis.Numbering}), of the body {@code function}, -1
   * for the main tree: VALUE where its cardinality is at most one and it may not break out, which the
   * conservative compile never has.
   */
  public RunForm form(int function, int id) {
    return RunForm.of(facts, function, id);
  }

  /**
   * The kind of the Call {@code id}, a freeze id, of the body {@code function}, -1 for the main tree:
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
   * Pushes the results for {@code in} into {@code out}, in the run {@code state} belongs to, with {@code parameters} in
   * the parameter slots, one value per slot in their order: true when they ran out, false when out stopped. The run
   * derives its unit limit {@code U_f} from {@link RunState#stackBytes()} at its start ({@link StackUnits}): at
   * {@code U_f} 0 or below, which a declared stack of 0 gives, this frame is the starter and main and every body run on
   * the trampoline; otherwise main runs in its run form, a value-form root's value emitted here, and a call whose units
   * reach {@code U_f} starts the trampoline. A run that exhausts its thread's stack ends with
   * {@link Termination#stack()}: the only place that catches a StackOverflowError, a backstop.
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

  /**
   * The main tree's path variant with no parameter, as {@link #run} runs its run form: the results for {@code in}, from
   * the root path, go into {@code out} each with its path. The tests compare it with {@link #run}.
   */
  boolean runPath(RunState state, Object in, PathSink out) {
    if (parameters != 0) {
      throw new IllegalArgumentException("the tree has " + parameters + " parameter slots; the run gave 0 values");
    }
    Object[] run = new Object[slots];
    long unitLimit = StackUnits.unitLimit(state.stackBytes(), nesting.callFreeDepth());
    state.limitUnits(unitLimit);
    try {
      if (unitLimit <= 0) {
        return Trampoline.runPath(state, root, run, in, Path.ROOT, out);
      }
      return root.runPath(state, run, in, Path.ROOT, out);
    } catch (StackOverflowError _) {
      throw Termination.stack();
    }
  }

  /** The program's frames: each call's units, each body's leaf-emit depth, {@code D_f} and {@code T}. */
  public Nesting nesting() {
    return nesting;
  }
}
