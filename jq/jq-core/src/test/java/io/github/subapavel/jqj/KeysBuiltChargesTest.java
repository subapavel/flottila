package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * An array whose length is known only when it is full, as {@code keys_unsorted} builds one, charges its container when
 * it starts and each element before it stores it (ADR-0035: an array CONTAINER plus ELEMENT per element kept): 64 B,
 * the measured fixed charge (bench/RESULTS.md, 2026-11-13), and 8 B per element. A read object's keys are views of its
 * document, so they charge nothing. jq-ref 1.8.2: {@code {"a":1,"b":2} | keys_unsorted} gives ["a","b"] (observed
 * 2026-11-14).
 */
class KeysBuiltChargesTest {
  @Test
  void keysUnsortedChargesAContainerAndEightBytesPerKey() {
    String filter = "keys_unsorted";
    JqEngine at = bytesBuilt(80);
    Workspace workspace = at.newWorkspace();
    JqValue input = read(workspace, "{\"a\":1,\"b\":2}").root();
    Results results = new Results();
    assertThat(at.compile(filter).run(input, NONE, results, workspace)).as("run's answer under 80 bytes built").isTrue();
    assertThat(results.values).as("results under 80 bytes built").singleElement().extracting(JqValue::toJson).isEqualTo("[\"a\",\"b\"]");

    JqEngine under = bytesBuilt(79);
    Workspace small = under.newWorkspace();
    JqValue again = read(small, "{\"a\":1,\"b\":2}").root();
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(again, NONE, none, small), Budget.BYTES_BUILT, "keys_unsorted under 79 bytes built");
    assertThat(none.values).as("results under 79 bytes built").isEmpty();
  }
}
