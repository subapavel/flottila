package dev.rail8.jq;

import static dev.rail8.jq.CallRuns.STACK;
import static dev.rail8.jq.CallRuns.onStack;
import static dev.rail8.jq.Inlining.ENGINE;
import static dev.rail8.jq.Inlining.results;
import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Recursion (ADR-0040 "Recursion", ADR-0045): a recursive group is a cyclic SCC of the call graph, where a call of a
 * filter parameter is no edge. In spec 2a lowering a call into one was a Stub; #107 lowers it to a Call to the group's
 * lifted function, so this class's Stub entries left with their Stubs, which is no weakening: a Stub entry asserted only
 * that the construct was not built yet (GLOSSARY "Stub"), and each filter it named is now asserted to run. A live
 * recursive definition that no call expands compiles, and a binding error is thrown before any lowering. jq-ref 1.8.2
 * (observed 2026-11-13): {@code def f: f; f} runs until killed and {@code def f: def g: f; g; f} ends with rc 134
 * "cannot allocate memory"; the engine runs each until its step budget ends it, one step per Call.
 * {@code def f(g): 1; def r: f(r); r} prints 1 (r calls itself inside the closure it passes, an edge r → r, though f
 * never runs it; also calls/closure-passed-to-itself-never-runs). The recursive definitions that compile are also
 * differential cases ({@code defs/recursive-def-*}, {@code defs/closure-of-the-same-def-is-not-recursion},
 * {@code calls/*}).
 */
class RecursionStubTest {

  /**
   * Direct, and mutual through a nested definition: each never returns, and with 1 000 steps the step budget ends it
   * (ADR-0030). The runs are on a thread of {@link CallRuns#STACK}, which holds a thousand live Calls before S2b.4 and
   * S2b.5 exist.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"def f: f; f", "def f: def g: f; g; f"})
  void aCallIntoARecursiveGroupRunsUntilTheStepBudgetEndsIt(String filter) {
    JqEngine engine = steps(1_000);
    Results none = new Results();
    onStack(STACK, () -> assertBudget(() -> engine.compile(filter).run(JqNull.NULL, NONE, none, engine.newWorkspace()), Budget.STEPS, filter + " with 1 000 steps"));
    assertThat(none.values).as("results of %s with 1 000 steps", filter).isEmpty();
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
   * not; and a recursive r that passes itself to a closure f never runs compiles and runs. jq-ref 1.8.2, observed
   * 2026-11-13: null, 1, 1, 1 and 1.
   */
  static List<Arguments> compiling() {
    return List.of(
        Arguments.of("def f(g): g; f(f(.))", List.of("null")),
        Arguments.of("def k(g): 1; def r: r; k(r)", List.of("1")),
        Arguments.of("def f: f; 1", List.of("1")),
        Arguments.of("def f: def g: f; g; 1", List.of("1")),
        Arguments.of("def f(g): 1; def r: f(r); r", List.of("1")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("compiling")
  void aRecursiveDefinitionNoCallExpandsCompiles(String filter, List<String> expected) {
    assertThat(results(filter)).as("results of %s on null", filter).isEqualTo(expected);
  }
}
