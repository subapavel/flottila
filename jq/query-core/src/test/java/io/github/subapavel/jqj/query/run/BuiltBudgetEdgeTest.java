package io.github.subapavel.jqj.query.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * The bytes-built budget ends a run only past it (ADR-0035: {@code chargeBuilt(n)} raises past the limit): a charge
 * that reaches the limit exactly is counted, and the next byte is refused.
 */
class BuiltBudgetEdgeTest {
  @Test
  void aChargeThatReachesTheLimitExactlyPasses() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, 10, Long.MAX_VALUE);
    state.chargeBuilt(6);
    state.chargeBuilt(4);
    assertThat(state.built()).as("bytes built after charges of 6 and 4 with a limit of 10").isEqualTo(10);
    Termination t = assertThatExceptionOfType(Termination.class).as("one byte more").isThrownBy(() -> state.chargeBuilt(1)).actual();
    assertThat(t.budget()).as("budget of one byte more").isEqualTo(Termination.Budget.BYTES_BUILT);
    assertThat(state.built()).as("bytes built after the refused byte").isEqualTo(10);
  }
}
