package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.FormatNumber;
import io.flottila.mapping.xdm.NumberPattern;
import io.flottila.mapping.xdm.XsDecimal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

/**
 * The function family {@code arithmetic} (ADR-0025 "Families", F-15), the basic functions of #72
 * ({@code add} to {@code min}), the math functions of #73 ({@code sqr} to {@code round}) and {@code
 * formatNum} of #74 over the formatting core (ADR-0003). All are single-value functions over {@code
 * xs:decimal} strings (ADR-0005): a number is read by {@link XsDecimal#parse}, the {@code
 * xs:decimal} lexical form with XML whitespace around it ignored, anything else is {@code
 * err:FORG0001} at the function box; the result is written in the XPath canonical form. The binders
 * are methods, not lambdas of the maps, so that PIT mutates them.
 */
final class ArithmeticFamily {

  /** The family's queue functions: it has none. */
  static final Map<String, QueueLibrary.Binder> QUEUE = Map.of();

  /** The family's single-value functions. */
  static final Map<String, SingleValueLibrary.Binder> VALUE =
      Map.ofEntries(
          Map.entry("abs", ArithmeticFamily::abs),
          Map.entry("add", ArithmeticFamily::add),
          Map.entry("ceil", ArithmeticFamily::ceil),
          Map.entry("divide", ArithmeticFamily::divide),
          Map.entry("equalsA", ArithmeticFamily::equalsA),
          Map.entry("floor", ArithmeticFamily::floor),
          Map.entry("formatNum", ArithmeticFamily::formatNum),
          Map.entry("greater", ArithmeticFamily::greater),
          Map.entry("inv", ArithmeticFamily::inv),
          Map.entry("less", ArithmeticFamily::less),
          Map.entry("max", ArithmeticFamily::max),
          Map.entry("min", ArithmeticFamily::min),
          Map.entry("multiply", ArithmeticFamily::multiply),
          Map.entry("neg", ArithmeticFamily::neg),
          Map.entry("power", ArithmeticFamily::power),
          Map.entry("round", ArithmeticFamily::round),
          Map.entry("sign", ArithmeticFamily::sign),
          Map.entry("sqr", ArithmeticFamily::sqr),
          Map.entry("sqrt", ArithmeticFamily::sqrt),
          Map.entry("subtract", ArithmeticFamily::subtract));

  /** The greatest exponent of an exact {@code power}; above it the power is an xs:double. */
  private static final int MAX_EXACT_EXPONENT = 1000;

  private ArithmeticFamily() {}

  static Node.OfString add(AuthoringId box, List<Node.OfString> args) {
    return binary("add", box, args, BigDecimal::add);
  }

  static Node.OfString subtract(AuthoringId box, List<Node.OfString> args) {
    return binary("subtract", box, args, BigDecimal::subtract);
  }

  static Node.OfString multiply(AuthoringId box, List<Node.OfString> args) {
    return binary("multiply", box, args, BigDecimal::multiply);
  }

  /** {@code divide(dividend, divisor)} by {@link #quotient}; zero is {@code err:FOAR0001}. */
  static Node.OfString divide(AuthoringId box, List<Node.OfString> args) {
    return binary("divide", box, args, (dividend, divisor) -> quotient(dividend, divisor, box));
  }

  static Node.OfString max(AuthoringId box, List<Node.OfString> args) {
    return binary("max", box, args, BigDecimal::max);
  }

  static Node.OfString min(AuthoringId box, List<Node.OfString> args) {
    return binary("min", box, args, BigDecimal::min);
  }

  static Node.OfString equalsA(AuthoringId box, List<Node.OfString> args) {
    return comparison("equalsA", box, args, order -> order == 0);
  }

  static Node.OfString greater(AuthoringId box, List<Node.OfString> args) {
    return comparison("greater", box, args, order -> order > 0);
  }

  static Node.OfString less(AuthoringId box, List<Node.OfString> args) {
    return comparison("less", box, args, order -> order < 0);
  }

  static Node.OfString abs(AuthoringId box, List<Node.OfString> args) {
    return unary("abs", box, args, BigDecimal::abs);
  }

  static Node.OfString neg(AuthoringId box, List<Node.OfString> args) {
    return unary("neg", box, args, BigDecimal::negate);
  }

  /** {@code inv}: 1 divided by the value, by {@link #quotient} (an assumed reading). */
  static Node.OfString inv(AuthoringId box, List<Node.OfString> args) {
    return unary("inv", box, args, value -> quotient(BigDecimal.ONE, value, box));
  }

  static Node.OfString sign(AuthoringId box, List<Node.OfString> args) {
    return unary("sign", box, args, value -> BigDecimal.valueOf(value.signum()));
  }

  /** {@code ceil(value)}: the least whole number not below the value. */
  static Node.OfString ceil(AuthoringId box, List<Node.OfString> args) {
    return unary("ceil", box, args, x -> x.setScale(0, RoundingMode.CEILING));
  }

  /** {@code floor(value)}: the greatest whole number not above the value. */
  static Node.OfString floor(AuthoringId box, List<Node.OfString> args) {
    return unary("floor", box, args, x -> x.setScale(0, RoundingMode.FLOOR));
  }

  /** {@code round(value)}: the nearest whole number, a half toward positive infinity. */
  static Node.OfString round(AuthoringId box, List<Node.OfString> args) {
    return unary("round", box, args, XsDecimal::round);
  }

  /** {@code sqr(value)}: the value times itself, exactly. */
  static Node.OfString sqr(AuthoringId box, List<Node.OfString> args) {
    return unary("sqr", box, args, x -> x.multiply(x));
  }

  /** {@code sqrt(value)}, computed in xs:double and cast back (ADR-0005). */
  static Node.OfString sqrt(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame ->
        fromDouble("sqrt", box, Math.sqrt(number("sqrt", box, value.eval(frame)).doubleValue()));
  }

  /**
   * {@code power(base, exponent)}: exact for a whole exponent from 0 to {@value
   * #MAX_EXACT_EXPONENT}, otherwise computed in xs:double and cast back (ADR-0005).
   */
  static Node.OfString power(AuthoringId box, List<Node.OfString> args) {
    Node.OfString base = args.get(0);
    Node.OfString exponent = args.get(1);
    return frame -> {
      BigDecimal b = number("power", box, base.eval(frame));
      BigDecimal e = number("power", box, exponent.eval(frame));
      if (e.signum() >= 0
          && e.compareTo(BigDecimal.valueOf(MAX_EXACT_EXPONENT)) <= 0
          && e.stripTrailingZeros().scale() <= 0) {
        return XsDecimal.canonical(b.pow(e.intValueExact()));
      }
      return fromDouble("power", box, Math.pow(b.doubleValue(), e.doubleValue()));
    };
  }

  /**
   * {@code formatNum(value, pattern)}: the number formatted as the DecimalFormat {@code pattern}
   * formats it, through the XPath picture the pattern compiles to (ADR-0003). The pattern is the
   * box's own string, which lowering has checked (ADR-0026, #74) and gives as a {@code Literal}:
   * that is compiled and analysed once, here (#194). A pattern node that is no literal is compiled
   * per value.
   *
   * @throws IllegalArgumentException when the pattern is a literal that compiles to no picture,
   *     which lowering refuses: a compiler bug
   */
  static Node.OfString formatNum(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString pattern = args.get(1);
    if (pattern instanceof Node.Literal(String literal)) {
      FormatNumber.Picture picture = FormatNumber.picture(NumberPattern.picture(literal));
      return frame -> picture.format(number("formatNum", box, value.eval(frame)));
    }
    return frame ->
        FormatNumber.format(
            number("formatNum", box, value.eval(frame)),
            NumberPattern.picture(pattern.eval(frame)));
  }

  /** The node of {@code function} over its one arg: {@code operation} of the number it gives. */
  private static Node.OfString unary(
      String function,
      AuthoringId box,
      List<Node.OfString> args,
      UnaryOperator<BigDecimal> operation) {
    Node.OfString value = args.getFirst();
    return frame -> XsDecimal.canonical(operation.apply(number(function, box, value.eval(frame))));
  }

  /** The node of {@code function} over two numbers, the result written canonically. */
  private static Node.OfString binary(
      String function,
      AuthoringId box,
      List<Node.OfString> args,
      BinaryOperator<BigDecimal> operation) {
    Node.OfString first = args.get(0);
    Node.OfString second = args.get(1);
    return frame ->
        XsDecimal.canonical(
            operation.apply(
                number(function, box, first.eval(frame)),
                number(function, box, second.eval(frame))));
  }

  /** The node of {@code function} over two numbers: {@code true} or {@code false}. */
  private static Node.OfString comparison(
      String function, AuthoringId box, List<Node.OfString> args, IntPredicate holds) {
    Node.OfString first = args.get(0);
    Node.OfString second = args.get(1);
    return frame ->
        Boolean.toString(
            holds.test(
                number(function, box, first.eval(frame))
                    .compareTo(number(function, box, second.eval(frame)))));
  }

  /**
   * ADR-0005 as amended by F-20, as Saxon-HE 12.5 divides: every quotient, one that terminates too,
   * rounded half-down to {@code max(18, scale of dividend - scale of divisor + 18)} fractional
   * digits, the scales without trailing zeros, so 1 / 2^30 is rounded (case fn/divide/004).
   *
   * @throws MappingRunException {@code err:FOAR0001} at {@code box} when {@code divisor} is zero
   */
  static BigDecimal quotient(BigDecimal dividend, BigDecimal divisor, AuthoringId box) {
    if (divisor.signum() == 0) {
      throw MappingRunException.dataError(RuntimeErrors.FOAR0001, box, "division by zero", null);
    }
    return XsDecimal.quotient(dividend, divisor);
  }

  /** The number {@code text} writes, or {@code err:FORG0001} at {@code box} when it is none. */
  private static BigDecimal number(String function, AuthoringId box, String text) {
    return XsDecimal.parse(text)
        .orElseThrow(() -> dataError(function, box, "'" + text + "' is no xs:decimal"));
  }

  /** The xs:decimal that {@code d} equals, or {@code err:FORG0001} when it is NaN or infinite. */
  private static String fromDouble(String function, AuthoringId box, double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw dataError(function, box, "the result is " + d + ", no xs:decimal");
    }
    return XsDecimal.canonical(new BigDecimal(d));
  }

  private static MappingRunException dataError(String function, AuthoringId box, String message) {
    return MappingRunException.dataError(
        RuntimeErrors.FORG0001, box, function + ": " + message, null);
  }
}
