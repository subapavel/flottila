package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.assertStub;
import static io.github.subapavel.jqj.Inlining.callChain;
import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0040 "Inline cap": with an expansion nested 512 deep the filter is a Stub, whatever the node budget. A call chain
 * of n definitions nests n expansions and lowers one node, so only the depth can stop it. Read here as: the 512th
 * nested expansion is the Stub, 511 compile. jq-ref 1.8.2 prints {@code null} for both (observed 2026-11-13); the
 * definitions are top-level, which the parser reads in a loop, so its 512 nesting levels (DEVIATIONS "Parse depth")
 * are not reached.
 */
class InlineDepthTest {

  @Test
  void expansionsNested511DeepCompile() {
    assertThat(results(callChain(511))).as("results of a 511-deep call chain on null").isEqualTo(List.of("null"));
  }

  @Test
  void anExpansionNested512DeepIsAStub() {
    assertStub(callChain(512));
  }
}
