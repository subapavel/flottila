package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import java.util.List;
import java.util.Map;

/**
 * The binding of the single-value functions (ADR-0025): a catalogue name, bound once per call site
 * when the interpreter compiles, by the binder of its function family (ADR-0025 "Families") to a
 * string node over its args' string nodes. Such a call stands in a {@code Lift}'s body, which runs
 * it once per slot, or, over single values only, is a single value of its own: a box's value, an
 * arg of another call, or an {@code AtomicToQueue}'s value (ADR-0006 since #83).
 */
final class SingleValueLibrary {

  /** A family's binding of one single-value function: the node of a call over its args. */
  @FunctionalInterface
  interface Binder {

    /**
     * The node of a call over {@code args}: the inputs, then the params, in catalogue order.
     *
     * @param box the call's function box, where its data error is raised (ADR-0026)
     */
    Node.OfString bind(AuthoringId box, List<Node.OfString> args);
  }

  /** The single-value functions of every family, by catalogue name. */
  private static final Map<String, Binder> BINDERS = FunctionFamilies.merge(FamilyIndex.VALUE);

  private SingleValueLibrary() {}

  /**
   * The node of a call of {@code name} over {@code args}, which the IR verifier has checked against
   * the call's {@code FunctionRef}: the inputs, then the params, in catalogue order.
   *
   * @param box the call's function box, where its data error is raised (ADR-0026)
   * @throws IllegalArgumentException when no single-value function is called {@code name}: lowering
   *     calls only functions of the catalogue, so this is a compiler bug
   */
  static Node.OfString bind(String name, AuthoringId box, List<Node.OfString> args) {
    Binder binder = BINDERS.get(name);
    if (binder == null) {
      throw new IllegalArgumentException(
          "the runtime library has no single-value function " + name);
    }
    return binder.bind(box, args);
  }
}
