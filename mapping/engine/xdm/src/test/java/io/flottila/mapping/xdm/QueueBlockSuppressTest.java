package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The queue block holds SUPPRESS (ADR-0004 as #51 shapes it): {@code long[] suppress} is a bitset
 * where bit {@code k} of word {@code k / 64} is set exactly when {@code values[k]} is null, no bit
 * stands at or past the slot count, and the array ends at its last word with a set bit, so a block
 * without SUPPRESS has an empty one. {@code QueueBlock.of(values, starts, suppress)} refuses any
 * other shape, the two-argument {@code of} refuses a null value, and {@code suppressed(slot)} reads
 * the bit of a slot index.
 */
class QueueBlockSuppressTest {

  private static final long BIT_0 = 1L;
  private static final long BIT_63 = 1L << 63;

  /** {@code n} values {@code "v<k>"}, with a null at the slot indexes given. */
  private static String[] values(int n, int... nulls) {
    String[] values = new String[n];
    for (int k = 0; k < n; k++) {
      values[k] = "v" + k;
    }
    for (int k : nulls) {
      values[k] = null;
    }
    return values;
  }

  /** The starts of one context holding all {@code n} slots. */
  private static int[] oneContext(int n) {
    return new int[] {0, n};
  }

  @Test
  void suppressedReadsTheBitOfEachSlot() {
    // worked example: [[SUPPRESS, "a"], [SUPPRESS]], bits 0 and 2
    QueueBlock block =
        QueueBlock.of(new String[] {null, "a", null}, new int[] {0, 2, 3}, new long[] {0b101L});

    assertThat(block.suppressed(0)).isTrue();
    assertThat(block.suppressed(1)).isFalse();
    assertThat(block.suppressed(2)).isTrue();
    assertThat(block.value(1)).isEqualTo("a");
    assertThat(read(block)).containsExactly(context(S, "a"), context(S));
    // a block without SUPPRESS has an empty bitset, and no slot of it is SUPPRESS
    QueueBlock none = QueueBlock.of(new String[] {"", "b"}, new int[] {0, 2}, new long[0]);
    assertThat(none.suppressed(0)).isFalse();
    assertThat(none.suppressed(1)).isFalse();
  }

  static Stream<Arguments> bitsWithAValue() {
    return Stream.of(
        Arguments.of("bit 0 over the value v0", values(1), oneContext(1), new long[] {BIT_0}),
        Arguments.of(
            "bit 1 over v1, beside a SUPPRESS at 0",
            values(2, 0),
            oneContext(2),
            new long[] {0b11L}),
        Arguments.of(
            "bit 64, in the second word, over v64",
            values(65, 0),
            oneContext(65),
            new long[] {BIT_0, BIT_0}),
        Arguments.of(
            "bit 0 over the value \"\"", new String[] {""}, oneContext(1), new long[] {1}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bitsWithAValue")
  void ofRefusesBitWithValue(String why, String[] values, int[] starts, long[] suppress) {
    // witness: the same starts with a null exactly under each set bit are accepted
    assertThatCode(() -> QueueBlock.of(new String[] {null}, oneContext(1), new long[] {BIT_0}))
        .doesNotThrowAnyException();
    assertThatCode(() -> QueueBlock.of(values(2, 0, 1), oneContext(2), new long[] {0b11L}))
        .doesNotThrowAnyException();
    assertThatCode(() -> QueueBlock.of(values(65, 0, 64), oneContext(65), new long[] {1, 1}))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> QueueBlock.of(values, starts, suppress))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> nullsWithoutABit() {
    return Stream.of(
        Arguments.of("a null and an empty bitset", values(1, 0), oneContext(1), new long[0]),
        Arguments.of(
            "a null at 1 and only bit 0", values(2, 0, 1), oneContext(2), new long[] {BIT_0}),
        Arguments.of(
            "a null at 64 and only bit 0", values(65, 0, 64), oneContext(65), new long[] {BIT_0}));
  }

  /** Bit k is set exactly when values[k] is null: a null without its bit is no SUPPRESS slot. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("nullsWithoutABit")
  void ofRefusesNullWithoutBit(String why, String[] values, int[] starts, long[] suppress) {
    assertThatThrownBy(() -> QueueBlock.of(values, starts, suppress))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoArgumentOfRefusesANullValue() {
    // witness: the empty string is a value, not SUPPRESS
    assertThatCode(() -> QueueBlock.of(new String[] {""}, oneContext(1)))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> QueueBlock.of(new String[] {null}, oneContext(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueueBlock.of(new String[] {"a", null}, new int[] {0, 1, 2}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> bitsPastTheSlots() {
    return Stream.of(
        Arguments.of(
            "bit 0 of a block without slots", new String[0], new int[] {0}, new long[] {1}),
        Arguments.of("bit 1 of one slot", values(1, 0), oneContext(1), new long[] {0b11L}),
        Arguments.of("bit 63 of three slots", values(3, 0), oneContext(3), new long[] {1 | BIT_63}),
        Arguments.of(
            "bit 64 of 64 slots", values(64, 63), oneContext(64), new long[] {BIT_63, BIT_0}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bitsPastTheSlots")
  void ofRefusesBitPastSlots(String why, String[] values, int[] starts, long[] suppress) {
    // witness: the last slot of a word, and the first of the next, are slots
    assertThatCode(() -> QueueBlock.of(values(64, 63), oneContext(64), new long[] {BIT_63}))
        .doesNotThrowAnyException();
    assertThatCode(() -> QueueBlock.of(values(65, 64), oneContext(65), new long[] {0, BIT_0}))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> QueueBlock.of(values, starts, suppress))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> trailingZeroWords() {
    return Stream.of(
        Arguments.of("one zero word, no SUPPRESS", values(1), oneContext(1), new long[] {0}),
        Arguments.of("one zero word, no slot", new String[0], new int[] {0}, new long[] {0}),
        Arguments.of(
            "a zero word after the last SUPPRESS word",
            values(70, 3),
            oneContext(70),
            new long[] {1L << 3, 0}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("trailingZeroWords")
  void ofRefusesTrailingZeroWord(String why, String[] values, int[] starts, long[] suppress) {
    // witness: the same blocks end at their last SUPPRESS word, and a zero word before it is kept
    assertThatCode(() -> QueueBlock.of(values(1), oneContext(1), new long[0]))
        .doesNotThrowAnyException();
    assertThatCode(() -> QueueBlock.of(values(70, 3), oneContext(70), new long[] {1L << 3}))
        .doesNotThrowAnyException();
    assertThatCode(() -> QueueBlock.of(values(70, 65), oneContext(70), new long[] {0, 0b10L}))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> QueueBlock.of(values, starts, suppress))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** One step of a builder run, which the reference repeats. */
  private sealed interface Step {}

  private record NewContext() implements Step {}

  private record Append(String value) implements Step {}

  private record AppendSuppress() implements Step {}

  /** Builds {@code steps} into a builder and into a list-of-contexts reference. */
  private static Queue build(String name, List<Step> steps, QueueBlock.Builder builder) {
    List<List<String>> reference = new ArrayList<>();
    for (Step step : steps) {
      switch (step) {
        case NewContext() -> {
          builder.newContext();
          reference.add(new ArrayList<>());
        }
        case Append(String value) -> {
          builder.append(value);
          reference.getLast().add(value);
        }
        case AppendSuppress() -> {
          builder.appendSuppress();
          reference.getLast().add(S);
        }
      }
    }
    return new Queue(name, reference);
  }

  /** The steps of a queue: a context opened before each context's slots. */
  private static List<Step> steps(Queue queue) {
    List<Step> steps = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      steps.add(new NewContext());
      for (String slot : context) {
        steps.add(slot == null ? new AppendSuppress() : new Append(slot));
      }
    }
    return steps;
  }

  static Stream<Arguments> builderRuns() {
    return SuppressQueues.queues().map(q -> Arguments.of(q.name(), q));
  }

  /**
   * Property test (ADR-0018): {@code newContext}, {@code append} and {@code appendSuppress} in the
   * order of an edge or a seeded queue, against a list-of-contexts reference. The built block reads
   * as the reference, and its own arrays, read back through the public API, satisfy the
   * three-argument {@code of}: the builder's bitset grows across words and ends at its last
   * SUPPRESS word.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("builderRuns")
  void builderWithAppendSuppressIsValid(String name, Queue queue) {
    QueueBlock.Builder builder = new QueueBlock.Builder();
    Queue reference = build(name, steps(queue), builder);

    QueueBlock block = builder.build();

    assertThat(read(block)).isEqualTo(reference.contexts());
    String[] values = new String[block.slots()];
    for (int i = 0; i < values.length; i++) {
      values[i] = block.suppressed(i) ? null : block.value(i);
    }
    int[] starts = new int[block.contexts() + 1];
    for (int c = 0; c < block.contexts(); c++) {
      starts[c] = block.start(c);
    }
    starts[block.contexts()] = block.slots();
    QueueBlock again = QueueBlock.of(values, starts, SuppressQueues.bits(values));
    assertThat(read(again)).isEqualTo(reference.contexts());
  }

  /** A seeded run that opens contexts and appends SUPPRESS in a random order of its own. */
  @Test
  void builderAppendsSuppressIntoTheContextOpenedLast() {
    RandomGenerator random = new SplittableRandom(51);
    List<Step> steps = new ArrayList<>();
    steps.add(new NewContext());
    for (int k = 0; k < 300; k++) {
      int pick = random.nextInt(6);
      steps.add(
          switch (pick) {
            case 0 -> new NewContext();
            case 1, 2 -> new AppendSuppress();
            default ->
                new Append(SuppressQueues.SAMPLE[random.nextInt(SuppressQueues.SAMPLE.length)]);
          });
    }
    QueueBlock.Builder builder = new QueueBlock.Builder();

    Queue reference = build("seed 51", steps, builder);

    assertThat(read(builder.build())).isEqualTo(reference.contexts());
    // witness: the run crosses several words of the bitset and holds SUPPRESS in more than one
    assertThat(reference.flat()).hasSizeGreaterThan(128);
    assertThat(reference.flat().stream().filter(v -> v == null).count()).isGreaterThan(64);
  }
}
