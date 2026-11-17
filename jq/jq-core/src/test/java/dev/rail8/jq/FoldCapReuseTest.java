package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.bytesBuilt;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * ADR-0035 "Folding at parse time" and "One fold budget per compile": each fold result is capped at 4 KiB, the cap
 * holds for the whole fold, so a merge of an object whose computed key the parser folded first counts that key's bytes,
 * and each fold has its own cap, whatever the folds of the same compile used before it. A fold under the cap is a Const,
 * which the run does not build, so it completes under 10 bytes built; one past it is left to the run, which builds and
 * charges it, so it ends with {@code JqTermination(BUDGET, BYTES_BUILT)}. The merge builds 64 + 2 * 16 bytes and its key
 * "y" * n charges STRING (40) plus n, so the merge's fold is 3 136 bytes for n = 3 000 and 4 236 for n = 4 100, past
 * 4 096 + 40. Each filter's {@code then} branch never runs; its key's fold, a pipe that is no constant, reads the key the
 * parser kept for the object inside it first. jq-ref 1.8.2 ({@code jq-ref -nc}, 2026-11-14) gives the object of the key
 * of n y's with 1 and "t" with 2, and "ab".
 */
class FoldCapReuseTest {
  /** The branch that never runs: a computed key whose fold reads the 3 040 bytes of the key kept inside it. */
  private static final String UNRUN = "if false then {(({((\"x\" * 3000)): \"v\"} | .[])): 2} else ";

  private static String merge(int n) {
    return UNRUN + "{((\"y\" * " + n + ")): 1} + {\"t\": 2} end";
  }

  /** Runs {@code filter} on null under 10 bytes built, its results in {@code results}; the run's answer. */
  private static boolean runUnder10(String filter, Results results) {
    JqEngine engine = bytesBuilt(10);
    return engine.compile(filter).run(JqNull.NULL, NONE, results, engine.newWorkspace());
  }

  @Test
  void aMergeUnderTheCapAfterAnotherFoldIsAConst() {
    Results results = new Results();
    assertThat(runUnder10(merge(3000), results)).as("run's answer for the merge of a 3 000-byte key under 10 bytes built").isTrue();
    assertThat(results.values).as("results of the merge of a 3 000-byte key").singleElement().extracting(JqValue::toJson).isEqualTo("{\"" + "y".repeat(3000) + "\":1,\"t\":2}");
  }

  @Test
  void aMergePastTheCapAfterAnotherFoldIsLeftToTheRun() {
    assertBudget(() -> runUnder10(merge(4100), new Results()), Budget.BYTES_BUILT, "the merge of a 4 100-byte key under 10 bytes built");
  }

  @Test
  void anOperatorAfterAKeptKeyIsAConstOfItsOwnBytes() {
    // "a" + "b" folds to 42 bytes; the key the run never builds, 3 040 bytes, was folded and read before it.
    Results results = new Results();
    String filter = "if false then {(\"x\" * 3000): 1} else (\"a\" + \"b\") end";
    assertThat(runUnder10(filter, results)).as("run's answer for %s under 10 bytes built", filter).isTrue();
    assertThat(results.values).as("results of %s", filter).singleElement().extracting(JqValue::toJson).isEqualTo("\"ab\"");
  }
}
