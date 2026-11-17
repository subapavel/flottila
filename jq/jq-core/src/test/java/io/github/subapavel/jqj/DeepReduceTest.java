package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
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
