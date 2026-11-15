package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic family's math functions (#73), called through {@link SingleValueLibrary} as a
 * {@code Lift}'s body calls them: numbers in the {@code xs:decimal} lexical form, results in the
 * XPath canonical form, and {@code err:FORG0001} at the function box otherwise. The corpus cases
 * {@code fn/sqr} to {@code fn/round} pin the same through the facade and against Saxon-HE.
 */
class ArithmeticFamilyTest {

  private static final AuthoringId BOX = new AuthoringId("V1");

  private static String call(String name, String... args) {
    List<Node.OfString> nodes =
        java.util.Arrays.stream(args).<Node.OfString>map(a -> f -> a).toList();
    return SingleValueLibrary.bind(name, BOX, nodes).eval(null);
  }

  private static void assertDataError(String name, String... args) {
    assertThatThrownBy(() -> call(name, args))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo(RuntimeErrors.FORG0001).isEqualTo("err:FORG0001");
              assertThat(e.authoringId()).isEqualTo(BOX);
              assertThat(e.getMessage()).startsWith("the function box V1: " + name + ": ");
            });
  }

  @Test
  void roundTakesAHalfTowardPositiveInfinity() {
    assertThat(call("round", "2.5")).isEqualTo("3");
    assertThat(call("round", "-2.5")).isEqualTo("-2");
    assertThat(call("round", "-0.5")).isEqualTo("0");
    assertThat(call("round", "-0.4")).isEqualTo("0");
    assertThat(call("round", "-0.6")).isEqualTo("-1");
    assertThat(call("round", "100")).isEqualTo("100");
  }

  @Test
  void ceilAndFloorGoToTheWholeNumberOnEachSide() {
    assertThat(call("ceil", "1.2")).isEqualTo("2");
    assertThat(call("ceil", "-1.2")).isEqualTo("-1");
    assertThat(call("ceil", "-0.5")).isEqualTo("0");
    assertThat(call("floor", "1.2")).isEqualTo("1");
    assertThat(call("floor", "-1.2")).isEqualTo("-2");
    assertThat(call("floor", "-0.0")).isEqualTo("0");
  }

  @Test
  void sqrIsExactAndCanonical() {
    assertThat(call("sqr", "1.5")).isEqualTo("2.25");
    assertThat(call("sqr", "10")).isEqualTo("100");
    assertThat(call("sqr", "-0.1")).isEqualTo("0.01");
    assertThat(call("sqr", " +.5\n")).isEqualTo("0.25");
    assertThat(call("sqr", "5.")).isEqualTo("25");
  }

  @Test
  void sqrtIsADoubleWrittenAsTheDecimalItEquals() {
    assertThat(call("sqrt", "4")).isEqualTo("2");
    assertThat(call("sqrt", "0")).isEqualTo("0");
    assertThat(call("sqrt", "2"))
        .isEqualTo("1.4142135623730951454746218587388284504413604736328125");
  }

  @Test
  void powerIsExactForAWholeExponentUpToOneThousand() {
    assertThat(call("power", "10", "25")).isEqualTo("10000000000000000000000000");
    assertThat(call("power", "0.1", "2.0")).isEqualTo("0.01");
    assertThat(call("power", "0", "0")).isEqualTo("1");
    assertThat(call("power", "1", "1000")).isEqualTo("1");
    assertThat(call("power", "3", "1000")).hasSize(478).startsWith("13220708194808066368");
  }

  @Test
  void powerIsADoubleOtherwise() {
    assertThat(call("power", "10", "-2"))
        .isEqualTo("0.01000000000000000020816681711721685132943093776702880859375");
    assertThat(call("power", "9", "0.5")).isEqualTo("3");
    // the exponent above 1000: 10^1001 would be exact, as a double it is 1E1001, Infinity
    assertDataError("power", "10", "1001");
    assertThat(call("power", "10", "-1"))
        .isEqualTo("0.1000000000000000055511151231257827021181583404541015625");
  }

  @Test
  void aResultThatIsNaNOrInfiniteIsADataError() {
    assertDataError("sqrt", "-1");
    assertDataError("power", "-8", "0.5");
    assertDataError("power", "0", "-1");
  }

  @Test
  void aValueThatIsNoDecimalIsADataErrorOfEveryFunction() {
    for (String value : List.of("", "abc", "1e3", "1,5", "--1", ".", "INF", "NaN", "1 2", "٣")) {
      assertDataError("sqr", value);
      assertDataError("sqrt", value);
      assertDataError("ceil", value);
      assertDataError("floor", value);
      assertDataError("round", value);
      assertDataError("power", value, "2");
      assertDataError("power", "2", value);
      assertDataError("formatNum", value, "0");
    }
  }

  /** formatNum (#74): the number formatted through the picture its pattern compiles to. */
  @Test
  void formatNumFormatsTheNumberAsItsPatternDoes() {
    assertThat(call("formatNum", " 1234.5 ", "#,##0.00")).isEqualTo("1,234.50");
    assertThat(call("formatNum", "-0.5", "0.#%")).isEqualTo("-50%");
    assertThat(call("formatNum", "0.5", "#.##")).isEqualTo("0.5");
  }

  /** A formatNum call whose pattern is the {@code Literal} lowering gives, its value per slot. */
  private static Node.OfString formatNumOverLiteral(String[] value, String pattern) {
    return SingleValueLibrary.bind(
        "formatNum", BOX, List.of(f -> value[0], new Node.Literal(pattern)));
  }

  /**
   * A literal pattern, as lowering gives every param, is compiled once, when the call binds (#194),
   * and formats each value of the call as a pattern compiled per value does.
   */
  @Test
  void formatNumOverALiteralPatternFormatsEveryValue() {
    String[] value = new String[1];
    Node.OfString call = formatNumOverLiteral(value, "#,##0.00;(#,##0.00)");

    for (String[] slot :
        List.of(
            new String[] {" 1234.5 ", "1,234.50"},
            new String[] {"-1234.5", "(1,234.50)"},
            new String[] {"0", "0.00"},
            new String[] {"7", "7.00"})) {
      value[0] = slot[0];
      assertThat(call.eval(null)).isEqualTo(slot[1]);
    }
    value[0] = "abc";
    assertThatThrownBy(() -> call.eval(null))
        .isInstanceOfSatisfying(
            MappingRunException.class, e -> assertThat(e.code()).isEqualTo(RuntimeErrors.FORG0001));
  }

  /**
   * A literal pattern that compiles to no picture, which lowering refuses (ADR-0003, ADR-0026), is
   * refused when the call binds, as {@code fixValues} refuses its table there: the call is never
   * built.
   */
  @Test
  void formatNumOverALiteralPatternWithoutAPictureIsRefusedWhenItBinds() {
    // witness: the same pattern over a node that is no literal binds, and is refused per value
    Node.OfString perValue =
        SingleValueLibrary.bind("formatNum", BOX, List.of(f -> "1", f -> "0x0"));
    assertThatThrownBy(() -> perValue.eval(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("the number pattern \"0x0\" has no XPath picture: ");

    assertThatThrownBy(() -> formatNumOverLiteral(new String[] {"1"}, "0x0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("the number pattern \"0x0\" has no XPath picture: ");
  }
}
