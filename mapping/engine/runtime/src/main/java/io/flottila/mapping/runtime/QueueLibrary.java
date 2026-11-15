package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.QueueDataException;
import java.util.List;
import java.util.Map;

/**
 * The binding of the runtime library (ADR-0025, refining ADR-0021's queue-operation library): a
 * queue function's catalogue name, bound once per call site when the interpreter compiles, by the
 * binder of its function family (ADR-0025 "Families"). A binder parses the params into the node, so
 * nothing is looked up by name or parsed while a mapping runs.
 */
final class QueueLibrary {

  /** A family's binding of one queue function: the node of a call over its inputs and params. */
  @FunctionalInterface
  interface Binder {

    /**
     * The node of a call over {@code inputs} with {@code params}.
     *
     * @param box the call's function box, where its data error is raised
     * @param params the strings of the call's params, in catalogue order
     */
    Node.OfQueue bind(AuthoringId box, List<Node.OfQueue> inputs, List<String> params);
  }

  /** The queue functions of every family, by catalogue name. */
  private static final Map<String, Binder> BINDERS = FunctionFamilies.merge(FamilyIndex.QUEUE);

  private QueueLibrary() {}

  /**
   * The node of a call of {@code name} over {@code inputs} with {@code params}, which the IR
   * verifier has checked against the call's {@code FunctionRef}.
   *
   * @param box the call's function box, where its data error is raised
   * @param params the strings of the call's params, in catalogue order
   * @throws IllegalArgumentException when the runtime library has no function {@code name}, or the
   *     function no such param value: lowering calls only functions of the catalogue, with values
   *     it lists, so this is a compiler bug
   */
  static Node.OfQueue bind(
      String name, AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Binder binder = BINDERS.get(name);
    if (binder == null) {
      throw new IllegalArgumentException("the runtime library has no function " + name);
    }
    return binder.bind(box, inputs, params);
  }

  /**
   * {@code node}, whose data error is rethrown as the runtime error {@code code} at {@code box}
   * ({@link MappingRunException#dataError}), with the algebra's message. An input's own data error
   * has already been rethrown at its own box, so only the call's reaches the catch.
   */
  static Node.OfQueue raising(String code, AuthoringId box, Node.OfQueue node) {
    return frame -> {
      try {
        return node.eval(frame);
      } catch (QueueDataException dataError) {
        throw MappingRunException.dataError(code, box, dataError.getMessage(), dataError);
      }
    };
  }
}
