package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.SAMPLE;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The queue algebra of the function family constant (#79, ADR-0025 "Families"): {@code copyValue}
 * gives a generating block, one context holding the slot at a position of the whole queue, counted
 * from 0 with context boundaries ignored; a SUPPRESS slot counts as a position and is copied as
 * SUPPRESS (observed on PO 7.5 and CPI, F-46, #217). A position past the last slot, an empty
 * queue's included, gives one empty context: assumed (ADR-0006). A position is an {@code
 * xs:nonNegativeInteger}. The property test (ADR-0018) is a seeded loop, without jqwik: it runs the
 * edges and 200 seeded queues against a reference written here.
 */
class ConstantFunctionsTest {

  /** A seeded queue: empty contexts, queues without contexts and SUPPRESS come up. */
  static Queue seeded(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    List<List<String>> contexts = new ArrayList<>();
    int count = random.nextInt(8) == 0 ? random.nextInt(30) : random.nextInt(5);
    for (int c = 0; c < count; c++) {
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(8) == 0 ? random.nextInt(70) : random.nextInt(4);
      for (int k = 0; k < slots; k++) {
        context.add(random.nextInt(5) == 0 ? S : SAMPLE[random.nextInt(SAMPLE.length)]);
      }
      contexts.add(Collections.unmodifiableList(context));
    }
    return new Queue("seed " + seed, List.copyOf(contexts));
  }

  static Stream<Arguments> queues() {
    Stream<Queue> edges =
        Stream.of(
            queue("no context"),
            queue("one empty context", context()),
            queue("only SUPPRESS", context(S), context(S, S)),
            queue(
                "SUPPRESS and an empty context", context("a", S), context(), context(S, "b", "")));
    return Stream.concat(
            edges, LongStream.rangeClosed(1, 200).mapToObj(ConstantFunctionsTest::seeded))
        .map(q -> Arguments.of(q.name(), q));
  }

  // --- property test ---

  /**
   * copyValue: one context, holding the slot at the position of the whole queue, context after
   * context, when the queue has it, SUPPRESS as SUPPRESS, and nothing otherwise; the input is
   * unchanged. The positions cross the last slot and the one past it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void copyValueIgnoresContextBoundaries(String name, Queue queue) {
    QueueBlock input = queue.block();
    List<String> flat = queue.flat();
    List<Integer> positions = new ArrayList<>(List.of(0, 1, 2, 3, 69, Integer.MAX_VALUE));
    positions.add(flat.size());
    if (!flat.isEmpty()) {
      positions.add(flat.size() - 1);
    }
    for (int position : positions) {
      List<List<String>> expected =
          List.of(position < flat.size() ? context(flat.get(position)) : context());

      QueueBlock result = ConstantFunctions.copyValue(input, position);

      assertThat(read(result)).as("position %d", position).isEqualTo(expected);
    }
    assertThat(read(input)).as("the input").isEqualTo(queue.contexts());
  }

  /** fn/copyValue/001–003: Sku in context Order is a, b, c | d | e, f (F-46). */
  @Test
  void copyValueSelectsThePositionAcrossTheWholeQueue() {
    QueueBlock skus = queue("Sku", context("a", "b", "c"), context("d"), context("e", "f")).block();

    assertThat(read(ConstantFunctions.copyValue(skus, 1))).isEqualTo(List.of(context("b")));
    assertThat(read(ConstantFunctions.copyValue(skus, 0))).isEqualTo(List.of(context("a")));
    // o2's context has no slot 3, the queue has: d
    assertThat(read(ConstantFunctions.copyValue(skus, 3))).isEqualTo(List.of(context("d")));
    // the last slot of the queue
    assertThat(read(ConstantFunctions.copyValue(skus, 5))).isEqualTo(List.of(context("f")));

    // a position past a context's last slot reads on in the next context, past an empty one too
    QueueBlock three = queue("three contexts", context("a", "b"), context("c"), context()).block();
    assertThat(read(ConstantFunctions.copyValue(three, 1))).isEqualTo(List.of(context("b")));
    assertThat(read(ConstantFunctions.copyValue(three, 2))).isEqualTo(List.of(context("c")));
  }

  /**
   * fn/copyValue/004: createIf(Tag) is "", SUPPRESS, "" | SUPPRESS, "" | (empty); Index 1 selects
   * SUPPRESS and copies it (F-46).
   */
  @Test
  void copyValueCopiesASuppressSlot() {
    QueueBlock tags = queue("createIf(Tag)", context("", S, ""), context(S, ""), context()).block();

    QueueBlock first = ConstantFunctions.copyValue(tags, 1);
    assertThat(read(first)).isEqualTo(List.of(context(S)));
    assertThat(SuppressQueues.suppressedSlots(first)).containsExactly(true);
    // the SUPPRESS slot of the second context, counted across the queue
    assertThat(read(ConstantFunctions.copyValue(tags, 3))).isEqualTo(List.of(context(S)));
    // witnesses: the values on both sides of them are copied as values
    QueueBlock value = ConstantFunctions.copyValue(tags, 0);
    assertThat(read(value)).isEqualTo(List.of(context("")));
    assertThat(SuppressQueues.suppressedSlots(value)).containsExactly(false);
    assertThat(read(ConstantFunctions.copyValue(tags, 4))).isEqualTo(List.of(context("")));
  }

  /** Not observed (F-46): the design's edge, one empty context, as a too-short context gave. */
  @Test
  void copyValuePastTheLastSlotOrOfAnEmptyQueueIsOneEmptyContext() {
    QueueBlock skus = queue("Sku", context("a", "b", "c"), context("d"), context("e", "f")).block();
    // witness: slot 5 is the last one
    assertThat(read(ConstantFunctions.copyValue(skus, 5))).isEqualTo(List.of(context("f")));
    for (int position : new int[] {6, 7, 69, Integer.MAX_VALUE}) {
      assertThat(read(ConstantFunctions.copyValue(skus, position)))
          .as("position %d", position)
          .isEqualTo(List.of(context()));
    }

    for (Queue empty :
        List.of(
            queue("no context"),
            queue("one empty context", context()),
            queue("two empty contexts", context(), context()))) {
      for (int position : new int[] {0, 1}) {
        assertThat(read(ConstantFunctions.copyValue(empty.block(), position)))
            .as("%s, position %d", empty.name(), position)
            .isEqualTo(List.of(context()));
      }
    }
  }

  // --- the position ---

  static Stream<Arguments> positions() {
    return Stream.of(
        Arguments.of("0", 0),
        Arguments.of("1", 1),
        Arguments.of("+7", 7),
        Arguments.of("-0", 0),
        Arguments.of("007", 7),
        Arguments.of(" 2 ", 2),
        Arguments.of("\t3\r\n", 3),
        Arguments.of("2147483647", Integer.MAX_VALUE),
        // beyond every context, as no context has more slots than an int counts
        Arguments.of("2147483648", Integer.MAX_VALUE),
        Arguments.of("99999999999999999999999", Integer.MAX_VALUE));
  }

  /**
   * The {@code xs:nonNegativeInteger} lexical form, XML whitespace around it ignored, as the
   * export's cast reads it.
   */
  @ParameterizedTest(name = "{index}: position {1}")
  @MethodSource("positions")
  void aPositionIsANonNegativeInteger(String text, int position) {
    assertThat(ConstantFunctions.position(text)).hasValue(position);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", " ", "-1", "-07", "1.0", "1e2", "one", "+", "1 2", " 1", "١"})
  void anyOtherTextIsNoPosition(String text) {
    assertThat(ConstantFunctions.position(text)).isEqualTo(OptionalInt.empty());
  }

  // --- interruption (ADR-0021) ---

  @Test
  void onAnInterruptedThreadCopyValueStopsBeforeAContext() {
    QueueBlock values = queue("values", context("1", S), context("2")).block();
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(ConstantFunctions.copyValue(values, 0)).isNotNull();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> ConstantFunctions.copyValue(values, 0))
          .isInstanceOf(CancellationException.class)
          .hasMessage("copyValue was running when the thread was interrupted");
    } finally {
      Thread.interrupted();
    }
  }
}
