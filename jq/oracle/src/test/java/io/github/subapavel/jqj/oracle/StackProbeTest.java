package io.github.subapavel.jqj.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/**
 * F-113: {@link StackProbe} judges the thread under test's count only between two equal references, on fake count
 * sequences through its package-private seam.
 */
class StackProbeTest {

  /** Every call of the fakes, in order: {@code r<n>} for a reference of n frames, {@code c<n>} for a count of n. */
  private final List<String> calls = new ArrayList<>();

  /** A supplier of {@code values} in turn, the last repeated, that logs each value it gives with {@code tag}. */
  private IntSupplier fake(String tag, int... values) {
    int[] next = {0};
    return () -> {
      int value = values[Math.min(next[0]++, values.length - 1)];
      calls.add(tag + value);
      return value;
    };
  }

  @Test
  void twoEqualReferencesJudgeTheCountBetweenThemAgainstFourFifths() {
    StackProbe.assertHolds(fake("r", 100, 100), fake("c", 80), "the runner");

    assertThat(calls).as("one reference, the count, one reference").containsExactly("r100", "c80", "r100");
  }

  @Test
  void unequalReferencesAreNotJudgedAndTheBracketIsTakenAgain() {
    StackProbe.assertHolds(fake("r", 50, 100, 100, 100), fake("c", 10, 80), "the runner");

    assertThat(calls)
        .as("an unstable bracket, then a stable one")
        .containsExactly("r50", "c10", "r100", "r100", "c80", "r100");
  }

  @Test
  void referencesThatNeverHoldStillFailWithEveryCountSeen() {
    int[] reference = {0};
    IntSupplier alternating =
        () -> {
          calls.add("r");
          return ++reference[0];
        };

    assertThatThrownBy(() -> StackProbe.assertHolds(alternating, fake("c", 7), "the runner"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("the runner")
        .hasMessageContaining("[1, 7, 2]")
        .hasMessageContaining("[" + (2 * StackProbe.TRIES - 1) + ", 7, " + 2 * StackProbe.TRIES + "]");
    assertThat(calls.stream().filter(c -> c.startsWith("c"))).as("the counts taken, one per try").hasSize(StackProbe.TRIES);
  }

  @Test
  void aCountBelowFourFifthsOfAStableReferenceFails() {
    assertThatThrownBy(() -> StackProbe.assertHolds(fake("r", 100, 100), fake("c", 79), "the runner"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("the runner")
        .hasMessageContaining("79")
        .hasMessageContaining("100");
  }
}
