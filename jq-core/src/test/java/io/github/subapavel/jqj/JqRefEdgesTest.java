package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Edges of number text, number order, array indexing and {@code tonumber} at {@code JqEngine.compile}, each one result
 * (#193): a computed number whose exponent has two digits, a lexeme that starts with its point, a signed word too short
 * for {@code inf} or {@code nan}, a computed index and {@code has} on a read array, and two read literals past a
 * double's range that are no canonical short token. Every expected text is jq-ref 1.8.2's, observed 2026-11-14 with
 * {@code jq-ref -nc '<filter>'}, or {@code printf '<input>' | jq-ref -c '<filter>'} where an input is given.
 */
class JqRefEdgesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  static Stream<Arguments> cases() {
    return Stream.of(
        // ADR-0012: a computed number's exponent shows at least two digits, so -10 needs no padding zero.
        Arguments.of("computed exponents", null, "[1e-10 + 0, 1e-9 + 0, 1.5e-11 + 0, 0.00000000012 * 1]", "[1e-10,1e-09,1.5e-11,1.2e-10]"),
        // ADR-0012: a lexeme with no integer part is read as 0 before its point.
        Arguments.of("point-first lexemes", null, "[.5, .5e1, 1.e2, 007]", "[0.5,5,1E+2,7]"),
        // A sign, then a word too short for inf or nan: no number.
        Arguments.of(
            "tonumber of short signed words",
            null,
            "[\"-in\", \"+na\", \"-i\", \"-sn\", \"+nan\", \"-inf\"] | map(try tonumber catch \"bad\")",
            "[\"bad\",\"bad\",\"bad\",\"bad\",null,-1.7976931348623157e+308]"),
        // A computed index steps to its element and no further; has(i) is whether that element exists.
        Arguments.of("computed index and has", "[10,20,30]", "[(1 as $i | .[$i]), (0 as $i | .[$i]), (2 as $i | .[$i]), (3 as $i | .[$i]), has(1), has(2), has(3)]", "[20,10,30,null,true,true,false]"),
        // ADR-0012: read literals that are no canonical short token compare as exact decimals, not as their infinities.
        Arguments.of(
            "read literals past a double",
            "[1e400, 2e400, 1.23456789E+400, 1.23456789E+401]",
            "[.[0] < .[1], .[0] == .[1], .[2] < .[3], .[2] == .[3]]",
            "[true,false,true,false]"),
        // jq's equality depth limit counts each nested object, built or read: 6 000 built wrappers over 4 000 read
        // levels compare, one wrapper more is too deep (jq-ref on the same input, printed by a bash generator).
        Arguments.of("equality at the depth limit", twoNested(4000), wrapped(6000), "true"),
        Arguments.of("equality past the depth limit", twoNested(4000), wrapped(6001), "\"Equality check too deep\""));
  }

  /** {@code [N,N]}, N being {@code levels} nested objects {@code {"a": ...}} around 0. */
  private static String twoNested(int levels) {
    String nested = "{\"a\":".repeat(levels) + "0" + "}".repeat(levels);
    return "[" + nested + "," + nested + "]";
  }

  /** The equality of the input's two elements, each under {@code wrappers} built objects {@code {a: ...}}, its error caught. */
  private static String wrapped(int wrappers) {
    String side = "(reduce range(" + wrappers + ") as $_ (.[%d]; {a: .}))";
    return "try (" + side.formatted(0) + " == " + side.formatted(1) + ") catch .";
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void eachEdgeGivesJqsResult(String what, String input, String filter, String expected) {
    Workspace workspace = ENGINE.newWorkspace();
    JqValue in = input == null ? JqNull.NULL : read(workspace, input).root();
    Results results = new Results();
    ENGINE.compile(filter).run(in, NONE, results, workspace);
    assertThat(results.values).as("results of %s", filter).singleElement().extracting(JqValue::toJson).isEqualTo(expected);
  }
}
