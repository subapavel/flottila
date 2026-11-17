package io.github.subapavel.jqj.internal.op;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.query.run.Termination.Budget;
import io.github.subapavel.jqj.query.run.Termination.Cause;
import org.junit.jupiter.api.Test;

/**
 * The growth of {@link Built.Elements}, the array keys, flatten and the array key on an array build (#72), as a
 * collector's (CollectOpGrowthTest): twice the length, at least {@link Built.Elements#FIRST} and at most {@link
 * Built#MAX_LENGTH}; a full array of {@code MAX_LENGTH} ends the run with the bytes-built termination (ADR-0035) instead
 * of allocating. The capacity is computed, never allocated, so the lengths near 2^31 cost nothing.
 */
class BuiltElementsTest {
  @Test
  void theCapacityDoublesFromFourUpToTheCeiling() {
    assertThat(Built.grown(new RunState(), 0)).as("capacity of the first slots").isEqualTo(4);
    assertThat(Built.grown(new RunState(), 2)).as("capacity after 2: twice 2 is under 4").isEqualTo(4);
    assertThat(Built.grown(new RunState(), 4)).as("capacity after 4").isEqualTo(8);
    assertThat(Built.grown(new RunState(), 1 << 30)).as("capacity after 2^30: twice is 2^31, past the ceiling").isEqualTo(Built.MAX_LENGTH);
    assertThat(Built.grown(new RunState(), Built.MAX_LENGTH - 1)).as("capacity after MAX_LENGTH - 1").isEqualTo(Built.MAX_LENGTH);
  }

  @Test
  void aFullArrayOfTheCeilingEndsWithBytesBuilt() {
    Termination t = assertThatExceptionOfType(Termination.class).as("growth after MAX_LENGTH").isThrownBy(() -> Built.grown(new RunState(), Built.MAX_LENGTH)).actual();
    assertThat(t.cause()).as("cause of the growth after MAX_LENGTH").isEqualTo(Cause.BUDGET);
    assertThat(t.budget()).as("budget of the growth after MAX_LENGTH").isEqualTo(Budget.BYTES_BUILT);
  }

  /** Five adds pass the first four slots; the array holds them in order, and sort orders only those added. */
  @Test
  void theElementsGrowPastTheFirstSlotsAndSortInPlace() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    Built.Elements elements = new Built.Elements(state);
    for (String s : new String[] {"e", "d", "c", "b", "a"}) {
      elements.add(BuiltString.of(s));
    }
    elements.sort((x, y) -> ((BuiltString) x).value().compareTo(((BuiltString) y).value()));
    BuiltArray array = elements.array();
    assertThat(array.toJson()).as("the five elements, sorted").isEqualTo("[\"a\",\"b\",\"c\",\"d\",\"e\"]");
  }
}
