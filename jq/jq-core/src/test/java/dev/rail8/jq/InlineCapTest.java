package dev.rail8.jq;

import static dev.rail8.jq.Inlining.doubling;
import static dev.rail8.jq.Inlining.lifted;
import static dev.rail8.jq.Inlining.results;
import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The inline cap (GLOSSARY "Inline cap", ADR-0040 and its #114 amendment): one budget per compile of {@code
 * Resolver.INLINE_CAP} = 65 536 nodes, each node lowered inside an expansion spending one. A call whose expansion would
 * pass it is a PLAIN Call to a lifted function, lowered once, which ticks one step (F-54); until #114 the filter was a
 * Stub there. The value is internal, so this test states it as a literal; a change of the cap is an ADR change and
 * changes this test with it.
 */
class InlineCapTest {

  /** ADR-0040: {@code Resolver.INLINE_CAP}. */
  private static final int CAP = 65_536;

  /** A step budget far below every run here that does not end: 2^25 or more steps each. */
  private static final long STEPS = 1_000_000;

  /**
   * The ticket's acceptance of #70, flipped by #114: a 26-level doubling chain would lower 2^27 - 1 nodes, so its call
   * is lifted; the compile lowers no more than the cap and each text once, so it ends within 20 s, and the run of 2^26
   * results ends with the step budget, {@code JqTermination(BUDGET, STEPS)}. Until #114 it was the cap's Stub.
   */
  @Test
  void aDoublingChainOf26DefinitionsEndsAtTheStepBudget() {
    JqEngine engine = steps(STEPS);
    JqFilter filter = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> engine.compile(doubling(26) + "f26"), "the compile of f26 ends within 20 s");
    assertBudget(() -> filter.run(JqNull.NULL, NONE, new Results(), engine.newWorkspace()), Budget.STEPS, "f26 with " + STEPS + " steps");
  }

  /**
   * {@code def f: .;} and CAP calls of f in one comma chain: each call lowers one {@code .}, CAP in all, so each fits
   * and none is lifted.
   */
  @Test
  void capCallsOfAOneNodeDefinitionCompile() {
    String filter = "def f: .; f" + ",f".repeat(CAP - 1);
    assertThat(results(filter)).as("results of %d calls of f on null", CAP).isEqualTo(Collections.nCopies(CAP, "null"));
    assertThat(lifted(filter)).as("lifted functions of %d calls of f", CAP).isEmpty();
  }

  /**
   * One more call would spend the (CAP + 1)th node, so it is a PLAIN Call, and the filter runs: DEVIATIONS "Long comma
   * chains", whose engine's column is 65 537 nulls; reference jq 1.8.2 rejects the chain with its bytecode limit, rc 3, {@code
   * function compiled to 524294 bytes which is too long} (observed 2026-11-15, -nc -f, .logs/114-jqref-probe.log).
   * Until #114 it was the cap's Stub.
   */
  @Test
  void oneCallMoreRuns() {
    String filter = "def f: .; f" + ",f".repeat(CAP);
    assertThat(results(filter)).as("results of %d calls of f on null", CAP + 1).isEqualTo(Collections.nCopies(CAP + 1, "null"));
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
   * within the cap, so nothing is lifted; reference jq 1.8.2 gives {@code [… f15]|length} 32768 (observed 2026-11-13).
   */
  @Test
  void aDoublingChainOf15DefinitionsCompiles() {
    assertThat(results(doubling(15) + "f15")).as("results of f15 on null").isEqualTo(Collections.nCopies(32_768, "null"));
    assertThat(lifted(doubling(15) + "f15")).as("lifted functions of f15").isEmpty();
  }

  /**
   * f16 would lower 2^17 - 1 = 131 071 nodes, past the cap, so its call is lifted and the chain runs: reference jq 1.8.2
   * prints 65 536 nulls and {@code [… f16]|length} 65536 (observed 2026-11-15, -nc -f, .logs/114-jqref-probe.log).
   * Until #114 it was the cap's Stub.
   */
  @Test
  void aDoublingChainOf16DefinitionsRuns() {
    assertThat(results(doubling(16) + "f16")).as("results of f16 on null").isEqualTo(Collections.nCopies(65_536, "null"));
  }

  /**
   * F-54: a PLAIN call ticks one step, so a chain of them cannot escape the step budget. {@code def f1: .; def f2:
   * f1|f1; …; f60} would run 2^59 calls and lower 2^60 - 1 nodes inlined; its calls are lifted, and with {@link #STEPS}
   * steps the run ends with {@code JqTermination(BUDGET, STEPS)} within 20 s, where calls that ticked nothing would
   * run for ever. Reference jq 1.8.2 runs the chain to f22, 2^21 calls, and prints {@code null} (F-54; diff/over-cap.rec
   * {@code over-cap/doubling-22}).
   */
  @Test
  void plainCallChainEndsAsStepBudget() {
    StringBuilder chain = new StringBuilder("def f1: .; ");
    for (int i = 2; i <= 60; i++) {
      chain.append("def f").append(i).append(": f").append(i - 1).append("|f").append(i - 1).append("; ");
    }
    String text = chain.append("f60").toString();
    JqEngine engine = steps(STEPS);
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          JqFilter filter = engine.compile(text);
          assertBudget(() -> filter.run(JqNull.NULL, NONE, new Results(), engine.newWorkspace()), Budget.STEPS, "f60 of the pipe chain with " + STEPS + " steps");
        },
        "the compile and the run of f60 of the pipe chain end within 20 s");
  }
}
