package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.lifted;
import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The inline cap per kind of node (GLOSSARY "Inline cap", ADR-0040: each node lowered inside an expansion spends one
 * of {@code Resolver.INLINE_CAP} = 65 536): a definition whose body has {@code nodes} nodes lowers that many at each
 * call, so the most calls of it that are inlined are 65 536 / nodes, rounded down, and run as jq does; with one call
 * more the call that would pass the cap is a PLAIN Call, f is lifted once and every later call of it is a Call too
 * (ADR-0040 #114 amendment), so the filter runs; until #114 it was the cap's Stub. Reference jq 1.8.2 rejects every one of
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

  /**
   * The node-cap edge of each kind the expansion measure counts (ADR-0040 "Amendment (ticket #114)", the #114 Standards
   * review): a call of f is inlined only while the nodes spent so far plus f's measured nodes fit the cap, so with
   * {@code spent} nodes lowered at each call the most calls inlined are {@code (65 536 - measured) / spent + 1}, and one
   * more is a PLAIN Call of f, lifted once, not a Stub. The measured nodes are the measure's arms: a node of the
   * construct's own and its parts' (a negation, an if, a try, a label, an interpolated string, a native call with its
   * arguments, a computed key's object); a Bind, a reduce and a foreach one more per pattern node; an object one per
   * pair and one more per shorthand key, {@code {a}} or {@code {"a"}} but not {@code {$x}} or {@code {$__loc__}}; a chain
   * one a step, its computed keys, bounds and interpolated names counted, its leading dot not; a variable, {@code
   * $__loc__} and a format one. Each equals the nodes spent. A wrong count moves the edge: one node more lifts f within
   * the most calls; one less inlines the call past the cap, where the backstop's Stub fires (F-90), when 65 536 leaves
   * a remainder of at least the count less one, so ExpansionMeasureTest#eachKindFitsTheBudgetLeftAtItsMeasuredNodes
   * pins each count exactly. Each body reaches no definition but f, so every lifting order lifts f and the row names it
   * (F-106). Each body's result on null is reference jq's (1.8.2, {@code def f: <body>; [f]} with -nc -f, observed
   * 2026-11-15, .logs/114-jqref-arms.log); an empty result is no result. The longer chains are past reference jq's
   * bytecode limit, as in {@link #theMostCallsOfABodyThatFitAreInlinedAndOneMoreLiftsIt}, so the counts are the jqj
   * column of DEVIATIONS "Long comma chains".
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '\'',
      textBlock =
          """
          -length                       => 2 => 2 => -0
          if . then 1 else 2 end        => 4 => 4 => 2
          if . then 1 end               => 3 => 3 => null
          try length catch 0            => 3 => 3 => 0
          try length                    => 2 => 2 => 0
          label $out | .                => 2 => 2 => null
          label $f | break $f           => 2 => 2 =>
          reduce . as $x (.; $x)        => 5 => 5 => null
          foreach . as $x (.; $x)       => 5 => 5 => null
          foreach . as $x (.; $x; $x)   => 6 => 6 => null
          . as [$a, $b] | $a            => 6 => 6 => null
          . as $x | $x                  => 4 => 4 => null
          "a\\(.)"                      => 2 => 2 => "anull"
          .[length]                     => 2 => 2 => null
          .[length:]                    => 2 => 2 => null
          .[:length]                    => 2 => 2 => null
          .[length:length]              => 3 => 3 => null
          ."\\(.)"                      => 3 => 3 => null
          ."a"                          => 1 => 1 => null
          length?                       => 2 => 2 => 0
          .[]?                          => 1 => 1 =>
          last(.)                       => 2 => 2 => null
          range(length; 1)              => 3 => 3 => 0
          {(tostring): .}               => 4 => 4 => {"null":null}
          {a}                           => 3 => 3 => {"a":null}
          {"a"}                         => 4 => 4 => {"a":null}
          {a: 1}                        => 3 => 3 => {"a":1}
          {"a\\(.)": 1}                 => 5 => 5 => {"anull":1}
          . as $x | {$x}                => 5 => 5 => {"x":null}
          {$__loc__}                    => 2 => 2 => {"__loc__":{"file":"<top-level>","line":1}}
          $__loc__                      => 1 => 1 => {"file":"<top-level>","line":1}
          @text                         => 1 => 1 => "null"
          """)
  void eachKindMeetsTheCapAtItsMeasuredNodes(String body, int measured, int spent, String result) {
    int most = (CAP - measured) / spent + 1;
    List<String> fits = lifted(calls(body, most));
    assertThat(fits).as("lifted functions of %d calls of def f: %s (%d nodes measured, %d spent)", most, body, measured, spent).doesNotContain("f/0");
    assertThat(results(calls(body, most))).as("results of %d calls of def f: %s on null", most, body).isEqualTo(result == null ? List.of() : Collections.nCopies(most, result));
    assertThat(lifted(calls(body, most + 1))).as("lifted functions of %d calls of def f: %s", most + 1, body).contains("f/0");
    assertThat(results(calls(body, most + 1))).as("results of %d calls of def f: %s on null", most + 1, body).isEqualTo(result == null ? List.of() : Collections.nCopies(most + 1, result));
  }

  /**
   * {@code ..} at the node-cap edge: it is the prelude's {@code recurse(.[]?)}, a call with a closure argument, whose
   * measure sums the lifted and the inlined form, so it counts the closure's node twice (two) while the lowering spends
   * one (the architect's ruling on nodes, #114). So 65 535 calls of {@code def f: ..} are inlined and 65 536 are not,
   * and the longer chain has more functions. At 65 535 calls there is one answer, every call of f inlined and only the
   * prelude's recursive {@code r/0} lifted, so that list is pinned. For the longer chain the test pins no name and no
   * count (F-106): f's body reaches the prelude's {@code recurse/0} and {@code recurse/1}, and with the cap the
   * compile's, greedy top-down lifts f and then them,
   * lowered with one node left, where another order may lift fewer; every order lifts at least one. Reference jq 1.8.2
   * gives null for {@code ..} on null (.logs/114-jqref-arms.log).
   */
  @Test
  void aRecurseCallMeetsTheCapAtItsMeasuredNodes() {
    int most = (CAP - 2) + 1;
    List<String> inlined = lifted(calls("..", most));
    assertThat(inlined).as("lifted functions of %d calls of def f: .., each call of f inlined", most).containsExactly("r/0");
    int fits = inlined.size();
    assertThat(results(calls("..", most))).as("results of %d calls of def f: .. on null", most).isEqualTo(Collections.nCopies(most, "null"));
    assertThat(lifted(calls("..", most + 1))).as("functions of %d calls of def f: .., %d with %d calls", most + 1, fits, most).hasSizeGreaterThan(fits);
    assertThat(results(calls("..", most + 1))).as("results of %d calls of def f: .. on null", most + 1).isEqualTo(Collections.nCopies(most + 1, "null"));
  }
}
