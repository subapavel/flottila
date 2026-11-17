package io.github.subapavel.jqj.query.internal.interpret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.query.run.Termination.Budget;
import io.github.subapavel.jqj.query.run.Termination.Cause;
import org.junit.jupiter.api.Test;

/**
 * A collector's growth (#64, the review fix of 56e4fc3): after {@code length} results fill its array, the next capacity
 * is twice {@code length}, at least 4 and at most {@code MAX_LENGTH}, the JDK's growth ceiling
 * {@code Integer.MAX_VALUE - 8}; a full array of {@code MAX_LENGTH} ends the run with the bytes-built termination
 * (ADR-0035) instead of allocating. The capacity is computed, never allocated, so the lengths near 2^31 cost nothing.
 * Doubling with an {@code int} overflows from 2^30 on, which is where the cap must take over.
 */
class CollectOpGrowthTest {
  @Test
  void theCapacityDoublesFromFourUpToTheCeiling() {
    assertThat(CollectOp.MAX_LENGTH).as("MAX_LENGTH, Integer.MAX_VALUE - 8").isEqualTo(2_147_483_639);
    assertThat(CollectOp.grown(new RunState(), 0)).as("capacity of the first array").isEqualTo(4);
    assertThat(CollectOp.grown(new RunState(), 2)).as("capacity after 2: twice 2 is under 4").isEqualTo(4);
    assertThat(CollectOp.grown(new RunState(), 4)).as("capacity after 4").isEqualTo(8);
    assertThat(CollectOp.grown(new RunState(), 1 << 29)).as("capacity after 2^29: 2^30, under the ceiling").isEqualTo(1_073_741_824);
    assertThat(CollectOp.grown(new RunState(), 1 << 30)).as("capacity after 2^30: twice is 2^31, past the ceiling").isEqualTo(2_147_483_639);
    assertThat(CollectOp.grown(new RunState(), 2_147_483_638)).as("capacity after MAX_LENGTH - 1").isEqualTo(2_147_483_639);
  }

  @Test
  void aFullArrayOfTheCeilingEndsWithBytesBuilt() {
    Termination t = assertThatExceptionOfType(Termination.class).as("growth after MAX_LENGTH").isThrownBy(() -> CollectOp.grown(new RunState(), 2_147_483_639)).actual();
    assertThat(t.cause()).as("cause of the growth after MAX_LENGTH").isEqualTo(Cause.BUDGET);
    assertThat(t.budget()).as("budget of the growth after MAX_LENGTH").isEqualTo(Budget.BYTES_BUILT);
    assertThat(t.getMessage()).as("message of the growth after MAX_LENGTH").isEqualTo("Bytes-built budget exceeded");
    assertThatExceptionOfType(Termination.class).as("growth after Integer.MAX_VALUE").isThrownBy(() -> CollectOp.grown(new RunState(), Integer.MAX_VALUE));
  }
}
