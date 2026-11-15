package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.lifted;
import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The inline cap per kind of node (GLOSSARY "Inline cap", ADR-0040: each node lowered inside an expansion spends one
 * of {@code Resolver.INLINE_CAP} = 65 536): a definition whose body has {@code nodes} nodes lowers that many at each
 * call, so the most calls of it that are inlined are 65 536 / nodes, rounded down, and run as jq does; with one call
 * more the call that would pass the cap is a PLAIN Call, f is lifted once and every later call of it is a Call too
 * (ADR-0040 #114 amendment), so the filter runs; until #114 it was the cap's Stub. jq-ref 1.8.2 rejects every one of
 * those longer chains with its bytecode limit, rc 3 ({@code function compiled to 524294 bytes which is too long} for
 * the one-node bodies, 174766 for the three-node ones and 104862 for the five-node ones, observed 2026-11-15 with -nc
 * -f, .logs/114-jqref-probe.log), so their results are the jqj column of DEVIATIONS "Long comma chains": one body's
 * result per call. Each body is of one kind: a parse constant, a native call, a chain of field steps, an operator chain, an
 * alternative chain, a destructuring and an object construction. A field step on the input has no target of its own
 * (the leading dot is the step's), so {@code .a.b.c} is three nodes. The value is internal, so this test states it as
 * a literal, as InlineCapTest does. Each body's result on null, one call of it, is the reference binary's (1.8.2,
 * 2026-11-14): {@code [null], [empty], [.a.b.c], [. + . + .], [. // . // .], [. as [$a] | .], [{a: .}]} on null give
 * {@code [null] [] [null] [null] [null] [null] [{"a":null}]}.
 */
class InlineCostKindsTest {
  /** ADR-0040: {@code Resolver.INLINE_CAP}. */
  private static final int CAP = 65_536;

  /** {@code def f: <body>; f,f,…} with {@code calls} calls of f. */
  private static String calls(String body, int calls) {
    return "def f: " + body + "; f" + ",f".repeat(calls - 1);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '\'',
      textBlock =
          """
          null                => 1 => null
          empty               => 1 =>
          .a.b.c              => 3 => null
          . + . + .           => 5 => null
          . // . // .         => 5 => null
          . as [$a] | .       => 5 => null
          {a: .}              => 3 => {"a":null}
          """)
  void theMostCallsOfABodyThatFitAreInlinedAndOneMoreLiftsIt(String body, int nodes, String result) {
    int most = CAP / nodes;
    List<String> expected = result == null ? List.of() : Collections.nCopies(most, result);
    assertThat(results(calls(body, most))).as("results of %d calls of def f: %s (%d nodes each) on null", most, body, nodes).isEqualTo(expected);
    assertThat(lifted(calls(body, most))).as("lifted functions of %d calls of def f: %s", most, body).isEmpty();
    List<String> oneMore = result == null ? List.of() : Collections.nCopies(most + 1, result);
    assertThat(results(calls(body, most + 1))).as("results of %d calls of def f: %s on null", most + 1, body).isEqualTo(oneMore);
    assertThat(lifted(calls(body, most + 1))).as("lifted functions of %d calls of def f: %s", most + 1, body).containsExactly("f/0");
  }
}
