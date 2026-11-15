package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.ConstantFunctions;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The function family {@code constant} (ADR-0025 "Families", F-15), #79 and #83: each catalogue
 * name to its binder. {@code copyValue} binds over the algebra in {@link ConstantFunctions}; its
 * position is parsed once, and one that is no {@code xs:nonNegativeInteger} stops the run with
 * {@code err:FORG0001} at the function box (ADR-0025, F-19), as the export does. {@code constant}
 * is its value. The binders are methods, not lambdas of the maps, so that PIT mutates them.
 */
final class ConstantFamily {

  /** The family's queue functions. */
  static final Map<String, QueueLibrary.Binder> QUEUE =
      Map.ofEntries(Map.entry("copyValue", ConstantFamily::copyValue));

  /** The family's single-value functions. */
  static final Map<String, SingleValueLibrary.Binder> VALUE =
      Map.ofEntries(Map.entry("constant", ConstantFamily::constant));

  private ConstantFamily() {}

  /**
   * {@code copyValue(values; position)}. When the position is no {@code xs:nonNegativeInteger}, the
   * node raises {@code err:FORG0001} at {@code box} without reading its input.
   */
  static Node.OfQueue copyValue(AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue values = inputs.getFirst();
    String text = params.getFirst();
    OptionalInt position = ConstantFunctions.position(text);
    if (position.isEmpty()) {
      String message = "copyValue: the position \"" + text + "\" is no xs:nonNegativeInteger";
      return frame -> {
        throw MappingRunException.dataError(RuntimeErrors.FORG0001, box, message, null);
      };
    }
    int at = position.getAsInt();
    return frame -> ConstantFunctions.copyValue(values.eval(frame), at);
  }

  /** {@code constant(; value)}: its value, a generating function (ADR-0006 since #83). */
  static Node.OfString constant(AuthoringId box, List<Node.OfString> args) {
    return args.getFirst();
  }
}
