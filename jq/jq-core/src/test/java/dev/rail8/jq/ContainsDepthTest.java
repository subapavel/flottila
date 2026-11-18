package dev.rail8.jq;

import static dev.rail8.jq.DeepRuns.assertJqError;
import static dev.rail8.jq.DeepRuns.assertResult;
import static dev.rail8.jq.DeepRuns.onSmallStack;
import static dev.rail8.jq.DeepRuns.value;
import static dev.rail8.jq.DeepRuns.wrap;
import static dev.rail8.jq.DeepRuns.wrapPair;

import dev.rail8.jq.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * jq's containment depth on built values: jq 1.8.2's {@code jv_contains} checks the depth of each pair first and raises
 * "Containment check too deep" past 10 000, then compares the kinds. Each witness is the pair {@code [a, b]} of two
 * chains that share no container, run as {@code a | contains(b)}, on DeepRuns' 256 KiB stack, half of the command
 * line's 512 KiB, so jq's error, never a StackOverflowError, shows the explicit stack. The expected results are jq-ref
 * 1.8.2's for the same chains built with {@code reduce}.
 */
class ContainsDepthTest {
  private static final String CONTAINS = ". as [$a, $b] | $a | contains($b)";
  private static final String DEEP = "Containment check too deep";

  @Test
  void arrayChainsAreCheckedUpTo10000Levels() {
    // jq-ref 1.8.2: true, then the error.
    JqValue at10000 = wrapPair(value("[0,0]"), "[", "]", 10_000);
    assertResult(onSmallStack(CONTAINS, at10000), "true", "two chains of 10 000 arrays around 0");
    assertJqError(onSmallStack(CONTAINS, wrapPair(at10000, "[", "]", 1)), DEEP, "two chains of 10 001 arrays around 0");
  }

  @Test
  void objectChainsAreCheckedUpTo10000Levels() {
    // jq-ref 1.8.2: true, then the error.
    JqValue at10000 = wrapPair(value("[{},{}]"), "{a:", "}", 10_000);
    assertResult(onSmallStack(CONTAINS, at10000), "true", "two chains of 10 000 objects around {}");
    assertJqError(onSmallStack(CONTAINS, wrapPair(at10000, "{a:", "}", 1)), DEEP, "two chains of 10 001 objects around {}");
  }

  @Test
  void theDepthIsCheckedBeforeTheKinds() {
    // jq-ref 1.8.2: false where the innermost kinds differ at 10 000 levels, the error at 10 001.
    JqValue at10000 = wrapPair(value("[0,\"x\"]"), "[", "]", 10_000);
    assertResult(onSmallStack(CONTAINS, at10000), "false", "chains of 10 000 arrays around 0 and \"x\"");
    assertJqError(onSmallStack(CONTAINS, wrapPair(at10000, "[", "]", 1)), DEEP, "chains of 10 001 arrays around 0 and \"x\"");
  }

  @Test
  void theWalkGoesOnAfterAMissAtTheBottom() {
    // jq-ref 1.8.2: [1,0] contains [0] after the pair (1, 0) misses, at 9 999 levels; one level more puts that pair
    // past the limit.
    JqValue at9999 = wrapPair(value("[[1,0],[0]]"), "[", "]", 9_999);
    assertResult(onSmallStack(CONTAINS, at9999), "true", "chains of 9 999 arrays around [1,0] and [0]");
    assertJqError(onSmallStack(CONTAINS, wrapPair(at9999, "[", "]", 1)), DEEP, "chains of 10 000 arrays around [1,0] and [0]");
  }

  @Test
  void aMillionLevelsGiveJqsErrorOnASmallStack() {
    // jq-ref 1.8.2: reduce range(1000000) as $i (0; [.]) | try contains(.) catch . gives the error.
    JqValue million = wrap(value("0"), "[", "]", 1_000_000);
    assertJqError(onSmallStack(". | contains(.)", million), DEEP, "10^6 arrays against themselves");
  }
}
