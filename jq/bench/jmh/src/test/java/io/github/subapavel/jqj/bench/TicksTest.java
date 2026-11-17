package io.github.subapavel.jqj.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.bench.Inputs.Size;
import org.junit.jupiter.api.Test;

/** {@link Ticks}: its count is the smallest step budget an operation completes under, and nothing else ends it. */
class TicksTest {

  private final Ticks.Scenario g2At1k = new Ticks.Scenario("G2.1k", Gates.G2, Inputs.orders(Size.K1));

  @Test
  void theCountCompletesAndOneStepFewerDoesNot() {
    Limits embedded = Limits.embedded();
    long ticks = Ticks.ticks(g2At1k, embedded);
    assertThat(ticks).isPositive();
    assertThat(Ticks.completes(g2At1k, ticks, embedded)).isTrue();
    assertThat(Ticks.completes(g2At1k, ticks - 1, embedded)).isFalse();
  }

  @Test
  void theScenariosAreG2G3AndG4InGatesOrder() {
    assertThat(Ticks.scenarios())
        .extracting(Ticks.Scenario::id)
        .containsExactly("G2.1k", "G2.100k", "G2.10m", "G3.100k", "G3.10m", "G4.1e4");
  }

  @Test
  void anOperationThatDoesNotCompleteUnderTheBaseBudgetIsAnError() {
    Limits embedded = Limits.embedded();
    Limits two = limits(2, embedded.bytesWritten());
    assertThatThrownBy(() -> Ticks.ticks(g2At1k, two))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("G2.1k does not complete within 2 steps");
  }

  @Test
  void anotherBudgetsTerminationIsNotCountedAsTooFewSteps() {
    Limits noOutput = limits(Limits.embedded().steps(), 0);
    assertThatThrownBy(() -> Ticks.completes(g2At1k, 1_000_000, noOutput))
        .isInstanceOfSatisfying(
            JqTermination.class, e -> assertThat(e.budget()).isEqualTo(JqTermination.Budget.BYTES_WRITTEN));
  }

  private static Limits limits(long steps, long bytesWritten) {
    Limits e = Limits.embedded();
    return new Limits(
        steps,
        e.deadline(),
        e.nestingDepth(),
        e.recursionDepth(),
        e.bytesBuilt(),
        bytesWritten,
        e.regexMatchTime(),
        e.stackBytes());
  }
}
