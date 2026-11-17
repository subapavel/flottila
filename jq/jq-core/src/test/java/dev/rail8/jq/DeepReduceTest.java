package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.unlimited;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * Java stack depth follows IR depth, never the iteration count (ADR-0041): a million iterations that each wrap the
 * state in an array run without a STACK termination or a StackOverflowError. jq-ref 1.8.2 gives 1
 * (reduce/deep-million, 2026-11-13).
 */
class DeepReduceTest {

  @Test
  void aMillionNestingIterationsGiveOne() {
    JqEngine engine = unlimited();
    Results all = new Results();
    assertThat(engine.compile("reduce range(0;1000000) as $i (0; [.]) | 1").run(JqNull.NULL, NONE, all, engine.newWorkspace()))
        .as("run's answer for the million-iteration reduce")
        .isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of the million-iteration reduce").containsExactly("1");
  }
}
