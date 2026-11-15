package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.BooleanFunctions;
import io.flottila.mapping.xdm.QueueDataException;
import java.util.List;
import java.util.Map;

/**
 * The function family {@code boolean} (ADR-0025 "Families", F-15): the single-value functions that
 * make or use a truth value (#77), and {@code ifWithoutElse}, a context function over the algebra
 * in {@link BooleanFunctions} (#78). Each value is cast as PO's {@code Bool.toBoolean} casts it
 * ({@link BooleanFunctions#truth}, observed on PO 7.5 and CPI, F-44): {@code 1} and {@code true} in
 * any case are true, {@code 0} and {@code false} in any case false, and any other value, the empty
 * string included, raises {@code err:FORG0001} at the box, as the export does. A result is written
 * {@code true} or {@code false}. The binders are methods, not lambdas of the map, so that PIT
 * mutates them.
 */
final class BooleanFamily {

  /** The family's queue functions. */
  static final Map<String, QueueLibrary.Binder> QUEUE =
      Map.of("ifWithoutElse", BooleanFamily::ifWithoutElse);

  /** The family's single-value functions. */
  static final Map<String, SingleValueLibrary.Binder> VALUE =
      Map.ofEntries(
          Map.entry("and", BooleanFamily::and),
          Map.entry("equals", BooleanFamily::equalsValue),
          Map.entry("if", BooleanFamily::ifValue),
          Map.entry("isNil", BooleanFamily::isNil),
          Map.entry("not", BooleanFamily::not),
          Map.entry("notEquals", BooleanFamily::notEquals),
          Map.entry("or", BooleanFamily::or));

  private BooleanFamily() {}

  /**
   * {@code and(a, b)}: true when both are true. Both are evaluated first, as every input of a
   * single-value call is (F-22, ADR-0006), and then both are cast, {@code a} first: {@code &}, not
   * {@code &&}.
   */
  static Node.OfString and(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> {
      String first = a.eval(frame);
      String second = b.eval(frame);
      return Boolean.toString(truth(box, first) & truth(box, second));
    };
  }

  /** {@code equals(a, b)}: whether the two cast values are the same truth value. */
  static Node.OfString equalsValue(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> {
      String first = a.eval(frame);
      String second = b.eval(frame);
      return Boolean.toString(truth(box, first) == truth(box, second));
    };
  }

  /**
   * {@code if(condition, then, else)}: {@code then} when the cast condition is true, else {@code
   * else}. All three are evaluated first, as every input of a single-value call is (F-22,
   * ADR-0006), so a data error in the untaken branch is raised at its box.
   */
  static Node.OfString ifValue(AuthoringId box, List<Node.OfString> args) {
    Node.OfString condition = args.get(0);
    Node.OfString then = args.get(1);
    Node.OfString otherwise = args.get(2);
    return frame -> {
      String test = condition.eval(frame);
      String thenValue = then.eval(frame);
      String elseValue = otherwise.eval(frame);
      return truth(box, test) ? thenValue : elseValue;
    };
  }

  /**
   * {@code ifWithoutElse(condition, then; keepSuppressValues)}: the algebra's, its cast error at
   * {@code box}. The parameter changes nothing PO 7.5 or CPI showed (F-44), so it is only checked.
   */
  static Node.OfQueue ifWithoutElse(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue condition = inputs.get(0);
    Node.OfQueue then = inputs.get(1);
    switch (params.getFirst()) {
      case "false", "true" -> {}
      default ->
          throw new IllegalArgumentException(
              "ifWithoutElse has no keepSuppressValues " + params.getFirst());
    }
    return QueueLibrary.raising(
        RuntimeErrors.FORG0001,
        box,
        frame -> BooleanFunctions.ifWithoutElse(condition.eval(frame), then.eval(frame)));
  }

  /**
   * {@code isNil(value)}: true for the empty string. The runtime reads {@code xsi:nil} as the empty
   * string (ADR-0008), so it cannot tell a nil field from an empty one.
   */
  static Node.OfString isNil(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame -> Boolean.toString(value.eval(frame).isEmpty());
  }

  static Node.OfString not(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame -> Boolean.toString(!truth(box, value.eval(frame)));
  }

  static Node.OfString notEquals(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> {
      String first = a.eval(frame);
      String second = b.eval(frame);
      return Boolean.toString(truth(box, first) != truth(box, second));
    };
  }

  /**
   * {@code or(a, b)}: true when one of them is true. Both are evaluated first, as every input of a
   * single-value call is (F-22, ADR-0006), and then both are cast, {@code a} first: {@code |}, not
   * {@code ||}.
   */
  static Node.OfString or(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> {
      String first = a.eval(frame);
      String second = b.eval(frame);
      return Boolean.toString(truth(box, first) | truth(box, second));
    };
  }

  /**
   * {@code text} cast as {@link BooleanFunctions#truth} casts it, or err:FORG0001 at {@code box}.
   */
  private static boolean truth(AuthoringId box, String text) {
    try {
      return BooleanFunctions.truth(text);
    } catch (QueueDataException noBoolean) {
      throw MappingRunException.dataError(
          RuntimeErrors.FORG0001, box, noBoolean.getMessage(), noBoolean);
    }
  }
}
