package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The queue block (ADR-0004) at its public API: {@code n} slots in {@code values}, {@code c + 1}
 * {@code contextStarts} with {@code [0] = 0}, {@code [c] = n} and no decrease. Indexes are the
 * arrays' own: context {@code k} spans {@code start(k)} to {@code end(k)}, slot {@code i} is {@code
 * value(i)}, both from 0. No SUPPRESS yet (#26).
 */
class QueueBlockTest {

  @Test
  void noContextsDiffersFromOneEmptyContext() {
    QueueBlock none = QueueBlock.of(new String[0], new int[] {0});
    QueueBlock oneEmpty = QueueBlock.of(new String[0], new int[] {0, 0});

    assertThat(none.contexts()).isZero();
    assertThat(none.slots()).isZero();
    assertThat(oneEmpty.contexts()).isEqualTo(1);
    assertThat(oneEmpty.slots()).isZero();
    assertThat(oneEmpty.start(0)).isZero();
    assertThat(oneEmpty.end(0)).isZero();

    // the builder makes the same distinction: no newContext() is no context at all
    QueueBlock built = new QueueBlock.Builder().build();
    QueueBlock.Builder oneContext = new QueueBlock.Builder();
    oneContext.newContext();
    QueueBlock builtEmpty = oneContext.build();
    assertThat(built.contexts()).isZero();
    assertThat(builtEmpty.contexts()).isEqualTo(1);
    assertThat(builtEmpty.slots()).isZero();
  }

  @Test
  void aBlockReadsItsContextsAndSlotsAsGiven() {
    // worked example: [["a", " b "], [], [""]]
    QueueBlock block = QueueBlock.of(new String[] {"a", " b ", ""}, new int[] {0, 2, 2, 3});

    assertThat(block.contexts()).isEqualTo(3);
    assertThat(block.slots()).isEqualTo(3);
    assertThat(block.start(0)).isZero();
    assertThat(block.end(0)).isEqualTo(2);
    assertThat(block.start(1)).isEqualTo(2);
    assertThat(block.end(1)).isEqualTo(2);
    assertThat(block.start(2)).isEqualTo(2);
    assertThat(block.end(2)).isEqualTo(3);
    assertThat(block.value(0)).isEqualTo("a");
    assertThat(block.value(1)).isEqualTo(" b ");
    assertThat(block.value(2)).isEmpty();
  }

  static Stream<Arguments> brokenStarts() {
    return Stream.of(
        Arguments.of("no entry, not even the sentinel", new String[0], new int[0]),
        Arguments.of("first start is not 0", new String[] {"a"}, new int[] {1, 1}),
        Arguments.of("a start decreases", new String[] {"a", "b"}, new int[] {0, 2, 1, 2}),
        Arguments.of("a negative start", new String[] {"a"}, new int[] {0, -1, 1}),
        Arguments.of("sentinel short of the slots", new String[] {"a", "b"}, new int[] {0, 1}),
        Arguments.of("sentinel past the slots", new String[] {"a"}, new int[] {0, 2}),
        Arguments.of("slots but no context", new String[] {"a"}, new int[] {0}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("brokenStarts")
  void ofRefusesStartsThatDecreaseOrMissTheSentinel(
      String why, String[] values, int[] contextStarts) {
    assertThatThrownBy(() -> QueueBlock.of(values, contextStarts))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> validStarts() {
    return Stream.of(
        // the smallest block whose starts are checked in a loop: one pass
        Arguments.of("one context", new String[] {"a"}, new int[] {0, 1}),
        Arguments.of("three contexts", new String[] {"a", " b ", ""}, new int[] {0, 2, 2, 3}));
  }

  /**
   * Every loop over contexts checks for interruption (ADR-0021), and {@code of} checks the starts
   * in one.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("validStarts")
  void ofOnAnInterruptedThreadThrowsCancellationExceptionAndGivesNoBlock(
      String why, String[] values, int[] contextStarts) {
    // witness: the same arrays give a block on a thread that is not interrupted
    assertThatCode(() -> QueueBlock.of(values, contextStarts)).doesNotThrowAnyException();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> QueueBlock.of(values, contextStarts))
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }
  }

  /** Values a source queue holds: {@code ""} is a value, and whitespace is part of it. */
  private static final String[] SAMPLE = {"", "a", " b ", "1", "Grüße", "x\ty"};

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /**
   * Property test (ADR-0018): a seeded random sequence of {@code newContext} and {@code append}
   * against a list-of-lists reference. Some contexts and some queues are long, to cross any growth
   * of the builder's arrays.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void builderAlwaysYieldsAValidBlock(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    List<List<String>> reference = new ArrayList<>();
    QueueBlock.Builder builder = new QueueBlock.Builder();
    int contexts = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(5);
    for (int c = 0; c < contexts; c++) {
      builder.newContext();
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(4);
      for (int k = 0; k < slots; k++) {
        String value = SAMPLE[random.nextInt(SAMPLE.length)];
        builder.append(value);
        context.add(value);
      }
      reference.add(context);
    }

    QueueBlock block = builder.build();

    assertThat(read(block)).isEqualTo(reference);
    // the block's own arrays satisfy ADR-0004, so of() accepts them and reads the same queue
    QueueBlock again = QueueBlock.of(values(block), starts(block));
    assertThat(read(again)).isEqualTo(reference);
  }

  /** The queue as contexts of values, checking ADR-0004's invariants on the way. */
  private static List<List<String>> read(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    int previousEnd = 0;
    for (int c = 0; c < block.contexts(); c++) {
      assertThat(block.start(c)).as("start(%d)", c).isEqualTo(previousEnd);
      assertThat(block.end(c)).as("end(%d)", c).isGreaterThanOrEqualTo(block.start(c));
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        context.add(block.value(i));
      }
      contexts.add(context);
      previousEnd = block.end(c);
    }
    assertThat(block.slots()).as("slots() is the sentinel").isEqualTo(previousEnd);
    return contexts;
  }

  private static String[] values(QueueBlock block) {
    String[] values = new String[block.slots()];
    for (int i = 0; i < values.length; i++) {
      values[i] = block.value(i);
    }
    return values;
  }

  private static int[] starts(QueueBlock block) {
    int[] starts = new int[block.contexts() + 1];
    for (int c = 0; c < block.contexts(); c++) {
      starts[c] = block.start(c);
    }
    starts[block.contexts()] = block.slots();
    return starts;
  }
}
