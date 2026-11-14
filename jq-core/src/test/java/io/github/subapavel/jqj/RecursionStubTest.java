package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.ENGINE;
import static io.github.subapavel.jqj.Inlining.assertStub;
import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Recursion in spec 2a (ADR-0040 "Recursion"): a recursive group is a cyclic SCC of the call graph, where a call of a
 * filter parameter is no edge; lowering a call into one is a Stub until spec 2b, which deletes these Stub entries with
 * their Stubs (that is no weakening). A live recursive definition that no call expands compiles, and a binding error
 * beats the Stub, since the errors are thrown before any lowering. jq-ref 1.8.2 (observed 2026-11-13): {@code def f:
 * f; f} runs until killed, {@code def f: def g: f; g; f} ends with rc 134 "cannot allocate memory", and {@code def
 * f(g): 1; def r: f(r); r} prints 1, which spec 2a gives up (r calls itself inside the closure it passes, an edge
 * r → r, though f never runs it). The recursive definitions that compile are also differential cases
 * ({@code defs/recursive-def-*}, {@code defs/closure-of-the-same-def-is-not-recursion}).
 */
class RecursionStubTest {

  /** Direct, mutual through a nested definition, and through a closure argument that is never run. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"def f: f; f", "def f: def g: f; g; f", "def f(g): 1; def r: f(r); r"})
  void aCallIntoARecursiveGroupIsAStub(String filter) {
    assertStub(filter);
  }

  /** jq-ref 1.8.2, observed 2026-11-13: rc 3 and this block; the reference to {@code zz} is f's level (ADR-0040). */
  @Test
  void aBindingErrorInARecursiveDefinitionBeatsTheStub() {
    String block = "jq: error: zz/0 is not defined at <top-level>, line 1, column 10:\n    def f: f|zz; f\n             ^^";
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile("def f: f|zz; f")).actual();
    assertThat(e.blocks()).as("blocks").containsExactly(block);
    assertThat(e.getMessage()).as("message").isEqualTo(block + "\njq: 1 compile error");
    assertThat(e.line()).as("line").isEqualTo(1);
    assertThat(e.column()).as("column").isEqualTo(10);
  }

  /**
   * f's closure argument calls f, but a call of the parameter g is no edge, so f is in no recursive group (ADR-0040
   * "Considered options": the expansion stack would flag it); a recursive definition never expanded compiles, live or
   * not. jq-ref 1.8.2, observed 2026-11-13: null, 1, 1 and 1.
   */
  static List<Arguments> compiling() {
    return List.of(
        Arguments.of("def f(g): g; f(f(.))", List.of("null")),
        Arguments.of("def k(g): 1; def r: r; k(r)", List.of("1")),
        Arguments.of("def f: f; 1", List.of("1")),
        Arguments.of("def f: def g: f; g; 1", List.of("1")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("compiling")
  void aRecursiveDefinitionNoCallExpandsCompiles(String filter, List<String> expected) {
    assertThat(results(filter)).as("results of %s on null", filter).isEqualTo(expected);
  }
}
