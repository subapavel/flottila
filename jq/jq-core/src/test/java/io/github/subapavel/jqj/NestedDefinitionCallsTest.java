package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ADR-0040 "Recursion": a recursive group is a cyclic SCC of the call graph, so definitions with nested definitions
 * that call earlier ones, and no call back, are in none: each compiles and runs as jq does. The programs mix a nested
 * definition called first or last, a definition that calls one defined before it from inside its own nested one,
 * nesting two deep, and two calls below main of one definition whose walk has already finished. Every expected text is jq-ref 1.8.2's ({@code jq-ref -nc '[<program>]'}, 2026-11-14).
 */
class NestedDefinitionCallsTest {
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '\'',
      textBlock =
          """
          def f: def g: 1; g; def h: def u: f; u; h                                       => [1]
          def f: def g: 1; g; def h: def a: 2; def u: f; a, u; h                          => [2,1]
          def f: def g: 1; g; def h: def u: f; def a: 2; a, u; h                          => [2,1]
          def f: def g: def k: 1; k; g; def h: def u: f; u; h                             => [1]
          def f: def g: 1; g; def h: def a: 2; def u: f; u, a; h, f                       => [1,2,1]
          def f: def g: 1; def k: 3; g, k; def h: def a: 2; def b: 4; def u: f; a, b, u; h => [2,4,1,3]
          def c: 1; def a: c; def b: c; a, b                                              => [1,1]
          """)
  void nestedDefinitionsWithoutACallBackRunAsJqRunsThem(String program, String expected) {
    assertThat(results("[" + program + "]")).as("results of [%s] on null", program).containsExactly(expected);
  }
}
