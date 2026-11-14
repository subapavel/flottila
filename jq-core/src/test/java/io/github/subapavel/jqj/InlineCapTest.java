package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.assertStub;
import static io.github.subapavel.jqj.Inlining.doubling;
import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The inline cap (GLOSSARY "Inline cap", ADR-0040): one budget per compile of {@code Resolver.INLINE_CAP} = 65 536
 * nodes, each node lowered inside an expansion spending one; past it the filter is a Stub. The value is internal, so
 * this test states it as a literal; a change of the cap is an ADR change and changes this test with it.
 */
class InlineCapTest {

  /** ADR-0040: {@code Resolver.INLINE_CAP}. */
  private static final int CAP = 65_536;

  /**
   * The ticket's acceptance: a 26-level doubling chain would lower 2^27 - 1 nodes; the cap stops it at its 65 537th,
   * so the compile ends soon with a Stub and never holds 2^26 nodes.
   */
  @Test
  void aDoublingChainOf26DefinitionsIsAStubAtTheCap() {
    assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertStub(doubling(26) + "f26"));
  }

  /** {@code def f: .;} and CAP calls of f in one comma chain: each call lowers one {@code .}, CAP in all. */
  @Test
  void capCallsOfAOneNodeDefinitionCompile() {
    String filter = "def f: .; f" + ",f".repeat(CAP - 1);
    assertThat(results(filter)).as("results of %d calls of f on null", CAP).isEqualTo(Collections.nCopies(CAP, "null"));
  }

  /** One more call spends the (CAP + 1)th node. */
  @Test
  void oneCallMoreIsAStub() {
    assertStub("def f: .; f" + ",f".repeat(CAP));
  }

  /**
   * The ticket's {@code [def f0: .; def f1: f0,f0; … f12]|length}: jq-ref 1.8.2 prints 4096 (observed 2026-11-13),
   * and {@code [… f12] == [range(4096)|null]} is true. {@code length} is #72's native, so the array is compared whole.
   */
  @Test
  void aDoublingChainOf12DefinitionsBuildsItsLeaves() {
    String expected = "[" + String.join(",", Collections.nCopies(4096, "null")) + "]";
    assertThat(results("[" + doubling(12) + "f12]")).as("results of [f12] on null").isEqualTo(List.of(expected));
  }

  /**
   * The cap's edge on a doubling chain: f15 lowers 2^16 - 1 = 65 535 nodes (its 32 768 leaves and 32 767 commas),
   * within the cap; jq-ref 1.8.2 gives {@code [… f15]|length} 32768 (observed 2026-11-13).
   */
  @Test
  void aDoublingChainOf15DefinitionsCompiles() {
    assertThat(results(doubling(15) + "f15")).as("results of f15 on null").isEqualTo(Collections.nCopies(32_768, "null"));
  }

  /** f16 lowers 2^17 - 1 = 131 071 nodes, past the cap. */
  @Test
  void aDoublingChainOf16DefinitionsIsAStub() {
    assertStub(doubling(16) + "f16");
  }
}
