package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A long run of top-level definitions binds and lowers without a StackOverflowError (#70 red): the parser reads them in
 * a loop but nests them as one {@code Def} in the next, so the Binder and the Resolver meet a chain as deep as the
 * run. jq-ref 1.8.2 (observed 2026-11-13) prints {@code [0,9989]} for {@code def f0: 0; …; def f9989: 9989; [f0,
 * f9989]} and rejects 10 000 such definitions with {@code memory exhausted} (its Bison stack, YYMAXDEPTH), so the test
 * stays at 9 990, inside jq's language.
 */
class DefChainTest {

  private static final int DEFINITIONS = 9_990;

  @Test
  void nineThousandNineHundredNinetyDefinitionsCompile() {
    StringBuilder f = new StringBuilder();
    for (int i = 0; i < DEFINITIONS; i++) {
      f.append("def f").append(i).append(": ").append(i).append("; ");
    }
    f.append("[f0, f").append(DEFINITIONS - 1).append(']');
    assertThat(results(f.toString())).as("results of %d definitions on null", DEFINITIONS).isEqualTo(List.of("[0,9989]"));
  }
}
