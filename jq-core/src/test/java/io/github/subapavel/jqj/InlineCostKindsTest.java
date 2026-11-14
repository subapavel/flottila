package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.ENGINE;
import static io.github.subapavel.jqj.Inlining.assertStub;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The inline cap per kind of node (GLOSSARY "Inline cap", ADR-0040: each node lowered inside an expansion spends one
 * of {@code Resolver.INLINE_CAP} = 65 536): a definition whose body has {@code nodes} nodes lowers that many at each
 * call, so the most calls of it that compile are 65 536 / nodes, rounded down, and one call more is a Stub. Each body is
 * of one kind: a parse constant, a native call, a chain of field steps, an operator chain, an alternative chain, a
 * destructuring and an object construction. A field step on the input has no target of its own (the leading dot is
 * the step's), so {@code .a.b.c} is three nodes. The value is internal, so this test states it as a literal, as
 * InlineCapTest does.
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
          null                => 1
          empty               => 1
          .a.b.c              => 3
          . + . + .           => 5
          . // . // .         => 5
          . as [$a] | .       => 5
          {a: .}              => 3
          """)
  void theMostCallsOfABodyThatCompileSpendItsNodesEach(String body, int nodes) {
    int most = CAP / nodes;
    assertThatCode(() -> ENGINE.compile(calls(body, most))).as("compile of %d calls of def f: %s (%d nodes each)", most, body, nodes).doesNotThrowAnyException();
    assertStub(calls(body, most + 1));
  }
}
