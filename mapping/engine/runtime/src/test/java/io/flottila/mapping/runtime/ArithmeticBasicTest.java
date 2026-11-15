package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The arithmetic family's single-value functions, bound through {@link SingleValueLibrary} as the
 * interpreter binds them (ADR-0025): xs:decimal in, the canonical form out (ADR-0005), and a data
 * error at the box the binding was given (ADR-0026, F-19), whichever box the args come from.
 */
class ArithmeticBasicTest {

  private static final AuthoringId BOX = new AuthoringId("box-1");

  private static String call(String name, AuthoringId box, String... args) {
    List<Node.OfString> nodes = Stream.of(args).<Node.OfString>map(a -> frame -> a).toList();
    return SingleValueLibrary.bind(name, box, nodes).eval(null);
  }

  private static String call(String name, String... args) {
    return call(name, BOX, args);
  }

  static Stream<Arguments> results() {
    return Stream.of(
        Arguments.of("add", List.of("0.1", "0.2"), "0.3"),
        Arguments.of("add", List.of("4.2", "-0.2"), "4"),
        Arguments.of("add", List.of(" +5 ", "\t.5\n"), "5.5"),
        Arguments.of("add", List.of("1.", "1"), "2"),
        Arguments.of("subtract", List.of("0.3", "0.1"), "0.2"),
        Arguments.of("subtract", List.of("1", "1.5"), "-0.5"),
        Arguments.of("multiply", List.of("0.1", "0.1"), "0.01"),
        Arguments.of("multiply", List.of("12345678901234567890", "10"), "123456789012345678900"),
        Arguments.of("multiply", List.of("0", "-5"), "0"),
        Arguments.of("divide", List.of("1", "3"), "0.333333333333333333"),
        Arguments.of("divide", List.of("2.0", "3"), "0.666666666666666667"),
        Arguments.of("divide", List.of("2.00", "3"), "0.666666666666666667"),
        Arguments.of("divide", List.of("0.1", "3"), "0.0333333333333333333"),
        Arguments.of("divide", List.of("0.10", "3"), "0.0333333333333333333"),
        Arguments.of("divide", List.of("1", "0.30"), "3.333333333333333333"),
        Arguments.of("divide", List.of("100", "3"), "33.333333333333333333"),
        Arguments.of("divide", List.of("4", "2"), "2"),
        Arguments.of("divide", List.of("1", "30000000000000000000001"), "0"),
        Arguments.of("divide", List.of("3", "524288"), "0.000005722045898437"),
        Arguments.of("divide", List.of("-7", "2"), "-3.5"),
        Arguments.of("equalsA", List.of("1", "1.0"), "true"),
        Arguments.of("equalsA", List.of("1", "2"), "false"),
        Arguments.of("greater", List.of("10", "9"), "true"),
        Arguments.of("greater", List.of("1", "1"), "false"),
        Arguments.of("greater", List.of("1", "2"), "false"),
        Arguments.of("less", List.of("9", "10"), "true"),
        Arguments.of("less", List.of("1", "1"), "false"),
        Arguments.of("less", List.of("2", "1"), "false"),
        Arguments.of("abs", List.of("-0.50"), "0.5"),
        Arguments.of("abs", List.of("3"), "3"),
        Arguments.of("neg", List.of("-0.5"), "0.5"),
        Arguments.of("neg", List.of("0"), "0"),
        Arguments.of("inv", List.of("4"), "0.25"),
        Arguments.of("inv", List.of("3"), "0.333333333333333333"),
        Arguments.of("sign", List.of("-5"), "-1"),
        Arguments.of("sign", List.of("0.0"), "0"),
        Arguments.of("sign", List.of("0.001"), "1"),
        Arguments.of("max", List.of("10", "9"), "10"),
        Arguments.of("max", List.of("-1", "-2"), "-1"),
        Arguments.of("min", List.of("10", "9"), "9"),
        Arguments.of("min", List.of("1.50", "1.5"), "1.5"));
  }

  @ParameterizedTest(name = "{0}{1} = {2}")
  @MethodSource("results")
  void aFunctionGivesTheCanonicalDecimal(String name, List<String> args, String expected) {
    assertThat(call(name, args.toArray(String[]::new))).isEqualTo(expected);
  }

  static Stream<Arguments> notNumbers() {
    return Stream.of(
        Arguments.of(""),
        Arguments.of(" "),
        Arguments.of("abc"),
        Arguments.of("1e3"),
        Arguments.of("NaN"),
        Arguments.of("+"),
        Arguments.of("."),
        Arguments.of("1 2"),
        Arguments.of("1,5"),
        Arguments.of("٣"),
        Arguments.of("1 "),
        Arguments.of("\u000b1"));
  }

  @ParameterizedTest
  @MethodSource("notNumbers")
  void aTextThatIsNoDecimalIsForg0001AtTheBoxOfTheCall(String text) {
    AuthoringId box = new AuthoringId("f-box");

    // either position of a two-input function, and the single-input ones
    for (String name : List.of("add", "subtract", "multiply", "divide", "equalsA", "greater")) {
      assertThatThrownBy(() -> call(name, box, text, "1")).isInstanceOf(MappingRunException.class);
      assertThatThrownBy(() -> call(name, box, "1", text))
          .isInstanceOfSatisfying(
              MappingRunException.class,
              e -> {
                assertThat(e.code()).isEqualTo(RuntimeErrors.FORG0001).isEqualTo("err:FORG0001");
                assertThat(e.authoringId()).isEqualTo(box);
                assertThat(e.getMessage()).startsWith("the function box f-box: ");
              });
    }
    for (String name : List.of("less", "max", "min")) {
      assertThatThrownBy(() -> call(name, box, text, "1")).isInstanceOf(MappingRunException.class);
    }
    for (String name : List.of("abs", "neg", "inv", "sign")) {
      assertThatThrownBy(() -> call(name, box, text))
          .isInstanceOfSatisfying(
              MappingRunException.class,
              e -> {
                assertThat(e.code()).isEqualTo("err:FORG0001");
                assertThat(e.authoringId()).isEqualTo(box);
              });
    }
  }

  @Test
  void aDivisionByZeroIsFoar0001AtTheBoxOfTheCall() {
    AuthoringId box = new AuthoringId("div-box");

    assertThatThrownBy(() -> call("divide", box, "7", "0.00"))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo(RuntimeErrors.FOAR0001).isEqualTo("err:FOAR0001");
              assertThat(e.authoringId()).isEqualTo(box);
              assertThat(e.getMessage()).isEqualTo("the function box div-box: division by zero");
            });
    assertThatThrownBy(() -> call("inv", new AuthoringId("inv-box"), "0"))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo("err:FOAR0001");
              assertThat(e.authoringId()).isEqualTo(new AuthoringId("inv-box"));
            });
    // a zero dividend is no error
    assertThat(call("divide", "0", "5")).isEqualTo("0");
  }

  @Test
  void twoCallSitesRaiseAtTheirOwnBoxes() {
    Node.OfString first =
        SingleValueLibrary.bind("add", new AuthoringId("one"), List.of(f -> "x", f -> "1"));
    Node.OfString second =
        SingleValueLibrary.bind("add", new AuthoringId("two"), List.of(f -> "x", f -> "1"));

    assertThatThrownBy(() -> first.eval(null))
        .isInstanceOfSatisfying(
            MappingRunException.class, e -> assertThat(e.authoringId().value()).isEqualTo("one"));
    assertThatThrownBy(() -> second.eval(null))
        .isInstanceOfSatisfying(
            MappingRunException.class, e -> assertThat(e.authoringId().value()).isEqualTo("two"));
  }

  @Test
  void theFamilyHoldsTheBasicFunctions() {
    assertThat(ArithmeticFamily.VALUE.keySet())
        .contains(
            "add",
            "subtract",
            "multiply",
            "divide",
            "equalsA",
            "greater",
            "less",
            "abs",
            "neg",
            "inv",
            "max",
            "min",
            "sign");
    assertThat(ArithmeticFamily.QUEUE).isEmpty();
  }
}
