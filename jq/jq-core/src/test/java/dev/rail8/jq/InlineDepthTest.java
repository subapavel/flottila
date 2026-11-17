package dev.rail8.jq;

import static dev.rail8.jq.Inlining.callChain;
import static dev.rail8.jq.Inlining.lifted;
import static dev.rail8.jq.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0040 "Inline cap" and its #114 amendment: the 512th nested expansion is a PLAIN Call to a lifted function, lowered
 * once from the worklist, where nesting restarts at 0 (F-54). A call chain of n definitions nests n expansions and
 * lowers one node, so only the nesting can lift it. 511 nested expansions are inlined; the 512th, a Stub until #114,
 * runs. Reference jq 1.8.2 prints {@code null} for both (observed 2026-11-13, and 2026-11-15 for 512 with -nc -f,
 * .logs/114-jqref-probe.log); the definitions are top-level, which the parser reads in a loop, so its 512 nesting
 * levels (DEVIATIONS "Parse depth") are not reached.
 */
class InlineDepthTest {

  /** {@code def f1: .+1; def f2: f1; …; def fn: f(n-1); fn}: a flat chain of n live definitions, one node of IR deep. */
  private static String flat(int n) {
    StringBuilder f = new StringBuilder("def f1: .+1; ");
    for (int i = 2; i <= n; i++) {
      f.append("def f").append(i).append(": f").append(i - 1).append("; ");
    }
    return f.append('f').append(n).toString();
  }

  /**
   * The fitting side of the nested-expansion count: 511 nested expansions are all inlined, so no function is lifted.
   * The pin rule allows naming the empty list (F-106): with every call inlined, no lifting order gives another.
   */
  @Test
  void expansionsNested511DeepCompile() {
    assertThat(results(callChain(511))).as("results of a 511-deep call chain on null").isEqualTo(List.of("null"));
    assertThat(lifted(callChain(511))).as("lifted functions of a 511-deep call chain").isEmpty();
  }

  /**
   * The 512th nested expansion, the Stub of this test until #114, is a PLAIN Call: the chain runs, and one function is
   * lifted, the one whose call was the 512th nested expansion (the design of #114).
   */
  @Test
  void anExpansionNested512DeepRuns() {
    assertThat(results(callChain(512))).as("results of a 512-deep call chain on null").isEqualTo(List.of("null"));
    assertThat(lifted(callChain(512))).as("lifted functions of a 512-deep call chain").hasSize(1);
  }

  /**
   * DEVIATIONS "Long definition runs" (row of {@code over-cap/flat-4097}): reference jq 1.8.2 rejects 4 097 live definitions,
   * rc 3, {@code too many function parameters or local function definitions (max 4095)} (recorded in
   * diff/over-cap.rec), and runs 4 095 to {@code 1} (.logs/114-jqref-probe.log). The engine has no such limit: past 511
   * nested expansions a call is PLAIN, so the chain runs and gives the row's engine column, {@code 1}.
   */
  @Test
  void aChainOf4097LiveDefinitionsRuns() {
    assertThat(results(flat(4_097))).as("results of a flat chain of 4097 definitions on null").isEqualTo(List.of("1"));
  }
}
