package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.DeepRuns.assertJqError;
import static io.github.subapavel.jqj.DeepRuns.assertResult;
import static io.github.subapavel.jqj.DeepRuns.onSmallStack;
import static io.github.subapavel.jqj.DeepRuns.value;
import static io.github.subapavel.jqj.DeepRuns.wrap;
import static io.github.subapavel.jqj.DeepRuns.wrapPair;
import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * jq's equality depth (ADR-0029, #65 owns jv_equal, design approval K1) on built values, reached through array
 * difference: {@code [x] - [y]} calls jv_equal(x, y), and {@code | [.[] | 1]} prints [1] for a kept x and [] for a
 * removed one ({@code ==} is #66's). jq 1.8.2 checks the depth first on every call and raises "Equality check too deep"
 * past 10 000, then compares the kinds, then identity. Every run is on a 256 KiB stack (DeepRuns), so jq's error, never a
 * StackOverflowError, shows the explicit stack. The expected results are jq-ref 1.8.2's recordings in diff/arith-depth.rec
 * (the reduce-* cases, which build the same chains with reduce), named at each test; the views' boundaries are the
 * differential cases of the same file.
 */
class EqualityDepthTest {
  private static final String DIFFERENCE = "[.[0]] - [.[1]] | [.[] | 1]";
  private static final String EQUAL = "Equality check too deep";

  @Test
  void twoEqualArrayChainsCompareUpTo10000Levels() {
    // reduce-equal-arrays-10000 gives [], reduce-equal-arrays-10001 the error.
    JqValue at10000 = wrapPair(value("[0,0]"), "[", "]", 10_000);
    assertResult(onSmallStack(DIFFERENCE, at10000), "[]", "two equal chains of 10 000 arrays");
    JqValue at10001 = wrapPair(at10000, "[", "]", 1);
    assertJqError(onSmallStack(DIFFERENCE, at10001), EQUAL, "two equal chains of 10 001 arrays");
  }

  @Test
  void twoEqualObjectChainsCompareUpTo10000Levels() {
    // reduce-equal-objects-10000 gives [], reduce-equal-objects-10001 the error.
    JqValue at10000 = wrapPair(value("[{},{}]"), "{a:", "}", 10_000);
    assertResult(onSmallStack(DIFFERENCE, at10000), "[]", "two equal chains of 10 000 objects");
    JqValue at10001 = wrapPair(at10000, "{a:", "}", 1);
    assertJqError(onSmallStack(DIFFERENCE, at10001), EQUAL, "two equal chains of 10 001 objects");
  }

  @Test
  void theDepthIsCheckedBeforeTheKinds() {
    // A chain against itself one level deeper meets a number and an array at its bottom: false at 10 000 levels, the
    // depth error at 10 001 before the kinds differ (reduce-depth-before-kind-10000 gives [1], -10001 the error).
    JqValue at10000 = wrap(value("0"), "[", "]", 10_000);
    assertResult(onSmallStack("[.] - [[.]] | [.[] | 1]", at10000), "[1]", "10 000 arrays against one more");
    JqValue at10001 = wrap(at10000, "[", "]", 1);
    assertJqError(onSmallStack("[.] - [[.]] | [.[] | 1]", at10001), EQUAL, "10 001 arrays against one more");
  }

  @Test
  void aMillionLevelsGiveJqsErrorOnASmallStack() {
    // reduce-depth-million: the error, after 10 001 levels of a walk that never reaches the bottom.
    JqValue million = wrap(value("0"), "[", "]", 1_000_000);
    assertJqError(onSmallStack("[.] - [[.]] | [.[] | 1]", million), EQUAL, "10^6 arrays against one more");
  }

  @Test
  void oneBuiltValueEqualsItselfWithoutAWalk() {
    // reduce-identity-built-10001: [], where two equal chains of that depth raise (twoEqualObjectChains...).
    JqValue at10001 = wrap(value("{}"), "{a:", "}", 10_001);
    assertResult(onSmallStack("[.] - [.] | [.[] | 1]", at10001), "[]", "a chain of 10 001 objects against itself");
  }

  @Test
  void aStaleViewIsNotEqualToItself() {
    // ADR-0026 and the #66 designer's reading of jvp_equal: identity is checked only after liveness, so a view of a
    // replaced document raises STALE_VIEW even against itself. The witnesses show that nothing before the equality
    // touches the view: on a live document the same filter gives [] (arith-depth/identity-root-10000 at depth 10 000),
    // and on the stale one [.] | [.[] | 1] completes.
    Workspace reading = TestDocuments.newWorkspace();
    JqValue live = read(reading, "[1,2]").root();
    Results ok = new Results();
    assertThat(DeepRuns.ENGINE.compile("[.] - [.] | [.[] | 1]").run(live, RunBudgets.NONE, ok, DeepRuns.ENGINE.newWorkspace())).as("run on a live view").isTrue();
    assertThat(ok.values.stream().map(JqValue::toJson).toList()).as("results on a live view").containsExactly("[]");

    read(reading, "[3]");
    Results collected = new Results();
    assertThat(DeepRuns.ENGINE.compile("[.] | [.[] | 1]").run(live, RunBudgets.NONE, collected, DeepRuns.ENGINE.newWorkspace())).as("collect of a stale view").isTrue();
    assertThat(collected.values.stream().map(JqValue::toJson).toList()).as("results of [.] | [.[] | 1] on a stale view").containsExactly("[1]");
    JqFilter difference = DeepRuns.ENGINE.compile("[.] - [.] | [.[] | 1]");
    assertStale(() -> difference.run(live, RunBudgets.NONE, new Results(), DeepRuns.ENGINE.newWorkspace()), "[.] - [.] of a stale view");
  }
}
