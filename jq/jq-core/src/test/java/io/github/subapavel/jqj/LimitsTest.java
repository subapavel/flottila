package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.time.Duration;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ADR-0017's budgets and ADR-0024's Unlimited: the values of Limits.embedded() and Limits.cli(), and what a Limits accepts. */
class LimitsTest {
  /** ADR-0024: Unlimited is a budget's largest value. */
  private static final long LARGEST = Long.MAX_VALUE;

  private static final Duration LONGEST = Duration.ofNanos(Long.MAX_VALUE);

  /** ADR-0017: 1 GiB of bytes built and of bytes written. */
  private static final long GIB = 1_073_741_824L;

  @Test
  void embeddedIsTheAdr0017Default() {
    Limits embedded = Limits.embedded();
    assertThat(embedded.steps()).as("steps").isEqualTo(1_000_000_000L);
    assertThat(embedded.deadline()).as("deadline").isEqualTo(Duration.ofSeconds(10));
    assertThat(embedded.nestingDepth()).as("nestingDepth").isEqualTo(10_000);
    assertThat(embedded.recursionDepth()).as("recursionDepth").isEqualTo(4_194_304);
    assertThat(embedded.bytesBuilt()).as("bytesBuilt").isEqualTo(GIB);
    assertThat(embedded.bytesWritten()).as("bytesWritten").isEqualTo(GIB);
    assertThat(embedded.regexMatchTime()).as("regexMatchTime").isEqualTo(Duration.ofSeconds(1));
    assertThat(embedded.stackBytes()).as("stackBytes").isEqualTo(524_288L);
  }

  @Test
  void cliDropsStepsDeadlineAndByteBudgets() {
    Limits cli = Limits.cli();
    assertThat(cli.steps()).as("steps").isEqualTo(LARGEST);
    assertThat(cli.deadline()).as("deadline").isEqualTo(LONGEST);
    assertThat(cli.bytesBuilt()).as("bytesBuilt").isEqualTo(LARGEST);
    assertThat(cli.bytesWritten()).as("bytesWritten").isEqualTo(LARGEST);
    // Kept from embedded(): the parse depth, recursion and regex limits and the stack bytes (ADR-0017).
    assertThat(cli.nestingDepth()).as("nestingDepth").isEqualTo(10_000);
    assertThat(cli.recursionDepth()).as("recursionDepth").isEqualTo(4_194_304);
    assertThat(cli.regexMatchTime()).as("regexMatchTime").isEqualTo(Duration.ofSeconds(1));
    assertThat(cli.stackBytes()).as("stackBytes").isEqualTo(524_288L);
  }

  @Test
  void unlimitedIsTheLargestValue() {
    assertThat(Limits.UNLIMITED).isEqualTo(LARGEST);
    assertThat(Limits.UNLIMITED_TIME).isEqualTo(LONGEST);

    Limits largest = new Limits(LARGEST, LONGEST, Integer.MAX_VALUE, Integer.MAX_VALUE, LARGEST, LARGEST, LONGEST, LARGEST);
    assertThat(largest.steps()).isEqualTo(LARGEST);
    assertThat(largest.deadline()).isEqualTo(LONGEST);
    assertThat(largest.nestingDepth()).isEqualTo(Integer.MAX_VALUE);
    assertThat(largest.recursionDepth()).isEqualTo(Integer.MAX_VALUE);
    assertThat(largest.bytesBuilt()).isEqualTo(LARGEST);
    assertThat(largest.bytesWritten()).isEqualTo(LARGEST);
    assertThat(largest.regexMatchTime()).isEqualTo(LONGEST);
    assertThat(largest.stackBytes()).isEqualTo(LARGEST);

    Duration longer = LONGEST.plusNanos(1);
    assertThatIllegalArgumentException().as("deadline").isThrownBy(() -> new Limits(0, longer, 0, 0, 0, 0, Duration.ZERO, 0));
    assertThatIllegalArgumentException().as("regexMatchTime").isThrownBy(() -> new Limits(0, Duration.ZERO, 0, 0, 0, 0, longer, 0));
  }

  @Test
  void zeroBudgetIsAccepted() {
    // ADR-0024: 0 is a legal budget (the backend oracle reruns with half the steps), never "no limit".
    Limits zero = new Limits(0, Duration.ZERO, 0, 0, 0, 0, Duration.ZERO, 0);
    assertThat(zero.steps()).isZero();
    assertThat(zero.deadline()).isEqualTo(Duration.ZERO);
    assertThat(zero.nestingDepth()).isZero();
    assertThat(zero.recursionDepth()).isZero();
    assertThat(zero.bytesBuilt()).isZero();
    assertThat(zero.bytesWritten()).isZero();
    assertThat(zero.regexMatchTime()).isEqualTo(Duration.ZERO);
    assertThat(zero.stackBytes()).isZero();
  }

  /** One component of Limits, named as its accessor. */
  enum Component {
    STEPS("steps"),
    DEADLINE("deadline"),
    NESTING_DEPTH("nestingDepth"),
    RECURSION_DEPTH("recursionDepth"),
    BYTES_BUILT("bytesBuilt"),
    BYTES_WRITTEN("bytesWritten"),
    REGEX_MATCH_TIME("regexMatchTime"),
    STACK_BYTES("stackBytes");

    final String accessor;

    Component(String accessor) {
      this.accessor = accessor;
    }
  }

  /** A value the component must reject and the exception that says so. */
  private record Invalid(Object value, Class<? extends RuntimeException> rejection) {}

  private static final int DRAWS = 64;

  static Stream<Arguments> seedsAndComponents() {
    return LongStream.rangeClosed(1, 16)
        .boxed()
        .flatMap(seed -> Arrays.stream(Component.values()).map(c -> argumentSet("seed " + seed + ", " + c.accessor, seed, c)));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("seedsAndComponents")
  void negativeNullOrOverlongBudgetIsRejected(long seed, Component component) {
    // Witness: every other component holds a valid value, so a rejection below is the drawn value's.
    assertThatCode(() -> with(component, null)).doesNotThrowAnyException();

    RandomGenerator random = new SplittableRandom(seed);
    for (int draw = 0; draw < DRAWS; draw++) {
      Invalid invalid = draw(component, random, draw);
      var rejected =
          assertThatExceptionOfType(invalid.rejection())
              .as("%s = %s (seed %d, draw %d)", component.accessor, invalid.value(), seed, draw)
              .isThrownBy(() -> with(component, invalid));
      if (invalid.value() == null) {
        // The null draw: the NPE names the component, so it is the null check's, not a later dereference's.
        rejected.withMessageContaining(component.accessor);
      }
    }
  }

  /** The first draws are the edges (-1, the smallest value, null, one nanosecond past Unlimited); the rest are random. */
  private static Invalid draw(Component component, RandomGenerator random, int draw) {
    return switch (component) {
      case STEPS, BYTES_BUILT, BYTES_WRITTEN, STACK_BYTES ->
          new Invalid(
              switch (draw) {
                case 0 -> -1L;
                case 1 -> Long.MIN_VALUE;
                default -> random.nextLong(Long.MIN_VALUE, 0L);
              },
              IllegalArgumentException.class);
      case NESTING_DEPTH, RECURSION_DEPTH ->
          new Invalid(
              switch (draw) {
                case 0 -> -1;
                case 1 -> Integer.MIN_VALUE;
                default -> random.nextInt(Integer.MIN_VALUE, 0);
              },
              IllegalArgumentException.class);
      case DEADLINE, REGEX_MATCH_TIME ->
          switch (draw) {
            case 0 -> new Invalid(null, NullPointerException.class);
            case 1 -> new Invalid(Duration.ofNanos(-1), IllegalArgumentException.class);
            case 2 -> new Invalid(LONGEST.plusNanos(1), IllegalArgumentException.class);
            default ->
                new Invalid(
                    random.nextBoolean()
                        ? Duration.ofSeconds(random.nextLong(Long.MIN_VALUE, 0L), random.nextLong(0L, 1_000_000_000L))
                        : LONGEST.plusNanos(random.nextLong(1L, Long.MAX_VALUE)),
                    IllegalArgumentException.class);
          };
    };
  }

  /** ADR-0017's embedded values, with {@code component} replaced by the invalid value (none when null). */
  private static Limits with(Component component, Invalid invalid) {
    Object v = invalid == null ? null : invalid.value();
    boolean replace = invalid != null;
    return new Limits(
        replace && component == Component.STEPS ? (Long) v : 1_000_000_000L,
        replace && component == Component.DEADLINE ? (Duration) v : Duration.ofSeconds(10),
        replace && component == Component.NESTING_DEPTH ? (Integer) v : 10_000,
        replace && component == Component.RECURSION_DEPTH ? (Integer) v : 4_194_304,
        replace && component == Component.BYTES_BUILT ? (Long) v : GIB,
        replace && component == Component.BYTES_WRITTEN ? (Long) v : GIB,
        replace && component == Component.REGEX_MATCH_TIME ? (Duration) v : Duration.ofSeconds(1),
        replace && component == Component.STACK_BYTES ? (Long) v : 524_288L);
  }
}
