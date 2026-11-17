package dev.rail8.jq.query.internal.interpret;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code break $name} (ADR-0008, ADR-0039 as amended by F-85): a Break marks the token its Label's run put in its slot
 * and answers false, with no result and no step. The answer is the Break's own constant; the mark is the run state's,
 * taken back by the Label of that token with {@link RunState#caught}. This holds the assertion "what a Break returns",
 * moved here from RunStateTest when {@code RunState.breakTo} lost its answer (#194).
 */
class BreakOpTest {
  @Test
  void marksItsTokenAndAnswersFalse() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    long outer = state.label();
    long token = state.label();
    // Witness: before the Break no Label takes a break back.
    assertThat(state.caught(token)).as("the Label of token %d before the Break", token).isFalse();

    // The Break reads slot 1, its Label's; slot 0 holds the outer Label's token.
    Object[] slots = {outer, token};
    List<Object> emitted = new ArrayList<>();
    boolean answer =
        new BreakOp(1)
            .run(
                state,
                slots,
                "in",
                v -> {
                  emitted.add(v);
                  return true;
                });

    assertThat(answer).as("what a Break returns").isFalse();
    assertThat(emitted).as("results of a Break").isEmpty();
    assertThat(state.steps()).as("steps of a Break").isZero();
    assertThat(state.caught(outer)).as("the outer Label of token %d after a break to %d", outer, token).isFalse();
    assertThat(state.caught(token)).as("the Label of token %d after its Break", token).isTrue();
  }
}
