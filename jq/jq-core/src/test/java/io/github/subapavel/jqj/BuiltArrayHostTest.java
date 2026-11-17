package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * A host reads a collected array through {@link JqArray} (ADR-0016, ADR-0026): {@code get(i)} outside [0,
 * {@code length()}) throws {@code IndexOutOfBoundsException}, also where the collector's storage has room past the
 * length (a collector grows to a capacity, ADR-0035: [1,2,3] fills three slots of four, five ones five of eight). The
 * expected values are jq-ref 1.8.2's, observed 2026-11-13: {@code [.[]]} on [1,2,3] gives [1,2,3].
 */
class BuiltArrayHostTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void aHostReadsACollectedArrayOnlyWithinItsLength() {
    JqArray three = collected("[1,2,3]");
    assertThat(three.length()).as("length of [1,2,3]").isEqualTo(3);
    assertThat(number(three.get(0))).as("get(0)").isEqualTo(1L);
    assertThat(number(three.get(2))).as("get(2), the last element").isEqualTo(3L);
    assertThatIndexOutOfBoundsException().as("get(3), the slot after the last").isThrownBy(() -> three.get(3));
    assertThatIndexOutOfBoundsException().as("get(-1)").isThrownBy(() -> three.get(-1));

    JqArray five = collected("[1,1,1,1,1]");
    assertThat(number(five.get(4))).as("get(4) of five ones").isEqualTo(1L);
    assertThatIndexOutOfBoundsException().as("get(5) of five ones").isThrownBy(() -> five.get(5));
    assertThatIndexOutOfBoundsException().as("get(7) of five ones").isThrownBy(() -> five.get(7));
  }

  /** The one result of {@code [.[]]} on the document {@code json}, read on a Workspace of its own: an array. */
  private static JqArray collected(String json) {
    JqValue input = read(ENGINE.newWorkspace(), json.getBytes(US_ASCII)).root();
    Results results = new Results();
    assertThat(ENGINE.compile("[.[]]").run(input, NONE, results, ENGINE.newWorkspace())).as("run's answer for [.[]] on %s", json).isTrue();
    assertThat(results.values).as("results of [.[]] on %s", json).singleElement().isInstanceOf(JqArray.class);
    return (JqArray) results.values.getFirst();
  }

  private static long number(JqValue value) {
    assertThat(value).as("a number").isInstanceOf(JqNumber.class);
    return ((JqNumber) value).longValue();
  }
}
