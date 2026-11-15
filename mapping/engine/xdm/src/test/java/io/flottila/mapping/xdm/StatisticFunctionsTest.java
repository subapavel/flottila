package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The queue algebra of the function family statistic (#75, ADR-0025 "Families"): {@code sum},
 * {@code average} and {@code count} give one value per context (#2 §5), and {@code average} of a
 * context without a value is division undefined (observed, F-45); {@code index} numbers every slot
 * per context or across the queue, SUPPRESS included (observed, F-45), and {@code counter} only the
 * values across the queue (decided, F-43). Numbers are {@code xs:decimal}s (ADR-0005); {@code sum},
 * {@code average} and {@code count} read no SUPPRESS (observed for count, F-45). The property tests
 * (ADR-0018) are seeded loops, without jqwik: each runs the edges and 200 seeded numeric queues
 * against a reference written here.
 */
class StatisticFunctionsTest {

  private static final BigDecimal ONE = BigDecimal.ONE;

  /** Numbers in the xs:decimal lexical form, some with XML whitespace or trailing zeros. */
  private static final String[] NUMBERS = {
    "0",
    "1",
    "-1",
    "2.5",
    "+3",
    "0.50",
    "-0.0",
    ".25",
    "7.",
    " 4 ",
    "\t-12.125\n",
    "1000000",
    "0.001",
    "99999999999999999999.99"
  };

  /** A seeded queue of numbers: empty contexts, queues without contexts and SUPPRESS come up. */
  static Queue numeric(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    List<List<String>> contexts = new ArrayList<>();
    int count = random.nextInt(8) == 0 ? random.nextInt(30) : random.nextInt(5);
    for (int c = 0; c < count; c++) {
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(8) == 0 ? random.nextInt(70) : random.nextInt(4);
      for (int k = 0; k < slots; k++) {
        context.add(random.nextInt(5) == 0 ? S : NUMBERS[random.nextInt(NUMBERS.length)]);
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
                "SUPPRESS across contexts and an empty context",
                context("1", S),
                context(),
                context(S, "2.5", "0")));
    return Stream.concat(
            edges,
            java.util.stream.LongStream.rangeClosed(1, 200)
                .mapToObj(StatisticFunctionsTest::numeric))
        .map(q -> Arguments.of(q.name(), q));
  }

  /** The values of {@code context}, SUPPRESS left out, as decimals. */
  private static List<BigDecimal> numbers(List<String> context) {
    List<BigDecimal> numbers = new ArrayList<>();
    for (String slot : context) {
      if (slot != S) {
        numbers.add(new BigDecimal(slot.strip()));
      }
    }
    return numbers;
  }

  private static BigDecimal total(List<BigDecimal> numbers) {
    return numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** The XPath canonical form: no exponent, no trailing zero, {@code 0} for every zero. */
  private static String canonical(BigDecimal value) {
    return value.compareTo(BigDecimal.ZERO) == 0 ? "0" : value.stripTrailingZeros().toPlainString();
  }

  // --- property tests ---

  /** sum: one context per context, holding the total of its values; an empty one gives 0. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void sumGivesOneTotalPerContext(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = StatisticFunctions.sum(input);

    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      expected.add(List.of(canonical(total(numbers(context)))));
    }
    assertThat(read(result)).isEqualTo(expected);
    // a block is immutable: the call leaves its input as it was
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  /** count: one context per context, holding the number of its values, SUPPRESS not counted. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void countGivesTheValuesPerContext(String name, Queue queue) {
    QueueBlock result = StatisticFunctions.count(queue.block());

    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      expected.add(List.of(String.valueOf(numbers(context).size())));
    }
    assertThat(read(result)).isEqualTo(expected);
  }

  /** Half a unit of the 18th fractional digit, ADR-0005's least quotient precision. */
  private static final BigDecimal HALF_UNIT_18 = new BigDecimal("5E-19");

  /**
   * average: one context per context, holding one canonical number within half a unit of the 18th
   * fractional digit of total / count (ADR-0005), checked against the total, not recomputed; a
   * context without a value gives an empty context. The exact digits and their rounding are the
   * literal examples of {@link #averageFollowsTheDivisionRuleOfAdr0005}. A queue with a context
   * without a value raises division undefined instead, naming the first such context.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void averageDividesTheTotalByTheCount(String name, Queue queue) {
    for (int c = 0; c < queue.contexts().size(); c++) {
      if (numbers(queue.contexts().get(c)).isEmpty()) {
        int context = c + 1;
        assertThatThrownBy(() -> StatisticFunctions.average(queue.block()))
            .isInstanceOf(ArithmeticException.class)
            .hasMessage("average: context " + context + " holds no value, division undefined");
        return;
      }
    }
    List<List<String>> result = read(StatisticFunctions.average(queue.block()));

    assertThat(result).hasSameSizeAs(queue.contexts());
    for (int c = 0; c < result.size(); c++) {
      List<BigDecimal> numbers = numbers(queue.contexts().get(c));
      assertThat(result.get(c)).as("context %d", c).hasSize(1);
      String text = result.get(c).getFirst();
      BigDecimal average = new BigDecimal(text);
      BigDecimal count = BigDecimal.valueOf(numbers.size());
      assertThat(text).as("context %d is canonical", c).isEqualTo(canonical(average));
      assertThat(average.multiply(count).subtract(total(numbers)).abs())
          .as("context %d: %s * %s against the total", c, text, count)
          .isLessThanOrEqualTo(count.multiply(HALF_UNIT_18));
    }
  }

  /**
   * index from 0 by 1: the contexts of its input; the k-th slot (from 0) of a context, or of the
   * queue without a reset, SUPPRESS included, is k itself, counted here, not computed. The decimal
   * and canonical forms of {@code initial + k * increment} are the literal examples of {@link
   * #indexWritesItsNumbersCanonically}.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void indexNumbersEverySlot(String name, Queue queue) {
    for (boolean reset : new boolean[] {true, false}) {
      QueueBlock result =
          StatisticFunctions.index(queue.block(), BigDecimal.ZERO, BigDecimal.ONE, reset);

      List<List<String>> expected = new ArrayList<>();
      long k = 0;
      for (List<String> context : queue.contexts()) {
        k = reset ? 0 : k;
        List<String> slots = new ArrayList<>();
        for (int j = 0; j < context.size(); j++) {
          slots.add(Long.toString(k++));
        }
        expected.add(slots);
      }
      assertThat(read(result)).as("reset %s", reset).isEqualTo(expected);
    }
  }

  /**
   * counter from 0 by 1: the contexts and SUPPRESS slots of its input; the k-th value (from 0) of
   * the queue is k itself, counted here, not computed.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void counterNumbersTheValuesAcrossTheQueue(String name, Queue queue) {
    QueueBlock result = StatisticFunctions.counter(queue.block(), BigDecimal.ZERO, BigDecimal.ONE);

    List<List<String>> expected = new ArrayList<>();
    long k = 0;
    for (List<String> context : queue.contexts()) {
      List<String> slots = new ArrayList<>();
      for (String slot : context) {
        slots.add(slot == S ? S : Long.toString(k++));
      }
      expected.add(slots);
    }
    assertThat(read(result)).isEqualTo(expected);
  }

  // --- examples ---

  @Test
  void sumIsExactAndCanonical() {
    QueueBlock values =
        queue(
                "q",
                context("1", "2.5"),
                context("1.50", "1.50"),
                context("-0.0"),
                context(" 2 ", "+1.", ".5"))
            .block();

    assertThat(read(StatisticFunctions.sum(values)))
        .containsExactly(context("3.5"), context("3"), context("0"), context("3.5"));
  }

  @Test
  void averageFollowsTheDivisionRuleOfAdr0005() {
    QueueBlock values =
        queue(
                "q",
                context("3", "3", "4"),
                context("2.0", "0", "0"),
                context("0.01", "0", "0"),
                context("100", "0", "0"),
                context("1", "2"),
                context("4", S))
            .block();

    assertThat(read(StatisticFunctions.average(values)))
        .containsExactly(
            context("3.333333333333333333"),
            context("0.666666666666666667"),
            context("0.00333333333333333333"),
            // 100 has no fractional digit, so a scale below 0 counts as 0: 18 digits
            context("33.333333333333333333"),
            context("1.5"),
            // SUPPRESS is no value: 4 / 1
            context("4"));
  }

  /**
   * An empty context, or one of SUPPRESS only, has no value to divide by: the call stops there, as
   * PO's {@code Stat.average} does with "Division undefined" (fn/average/003, F-45).
   */
  @Test
  void averageOfAContextWithoutAValueIsDivisionUndefined() {
    assertThatThrownBy(
            () ->
                StatisticFunctions.average(queue("q", context("1"), context(), context()).block()))
        .isInstanceOf(ArithmeticException.class)
        .hasMessage("average: context 2 holds no value, division undefined");
    assertThatThrownBy(
            () -> StatisticFunctions.average(queue("q", context(S, S), context("2")).block()))
        .isInstanceOf(ArithmeticException.class)
        .hasMessage("average: context 1 holds no value, division undefined");
    // a queue without a context divides nothing
    assertThat(read(StatisticFunctions.average(queue("q").block()))).isEmpty();
  }

  @Test
  void countCountsTheEmptyStringAndAnyTextButNotSuppress() {
    QueueBlock values = queue("q", context("", "x", S, "1e3"), context(S)).block();

    assertThat(read(StatisticFunctions.count(values))).containsExactly(context("3"), context("0"));
  }

  /** index numbers SUPPRESS as a value (fn/index/004, F-45); counter keeps it without a number. */
  @Test
  void indexNumbersSuppressAndKeepsTheContexts() {
    QueueBlock values = queue("q", context("a", S, "b"), context(), context(S, "c")).block();

    assertThat(read(StatisticFunctions.index(values, ONE, ONE, true)))
        .containsExactly(context("1", "2", "3"), context(), context("1", "2"));
    assertThat(
            read(
                StatisticFunctions.index(
                    values, new BigDecimal("10"), new BigDecimal("-3"), false)))
        .containsExactly(context("10", "7", "4"), context(), context("1", "-2"));
    assertThat(read(StatisticFunctions.counter(values, new BigDecimal("10"), new BigDecimal("-3"))))
        .containsExactly(context("10", S, "7"), context(), context(S, "4"));
  }

  @Test
  void indexWritesItsNumbersCanonically() {
    QueueBlock values = queue("q", context("a", "b", "c"), context(S, "d", "e", "f")).block();

    assertThat(
            read(
                StatisticFunctions.index(
                    values, new BigDecimal("1.5"), new BigDecimal("-0.5"), false)))
        .containsExactly(context("1.5", "1", "0.5"), context("0", "-0.5", "-1", "-1.5"));
    assertThat(
            read(
                StatisticFunctions.index(
                    values, new BigDecimal("0.10"), new BigDecimal("0.10"), true)))
        .containsExactly(context("0.1", "0.2", "0.3"), context("0.1", "0.2", "0.3", "0.4"));
    assertThat(
            read(StatisticFunctions.counter(values, new BigDecimal("1.5"), new BigDecimal("-0.5"))))
        .containsExactly(context("1.5", "1", "0.5"), context(S, "0", "-0.5", "-1"));
  }

  static Stream<Arguments> malformed() {
    return Stream.of(
        Arguments.of("1e3"),
        Arguments.of(""),
        Arguments.of(" "),
        Arguments.of("+"),
        Arguments.of("."),
        Arguments.of("1 2"),
        Arguments.of("NaN"),
        Arguments.of("INF"),
        Arguments.of("1,5"),
        Arguments.of(" 1"),
        Arguments.of("٣"));
  }

  /** A value that is not in the xs:decimal lexical form is a data error naming the function. */
  @ParameterizedTest(name = "\"{0}\"")
  @MethodSource("malformed")
  void aValueThatIsNoDecimalIsADataError(String value) {
    QueueBlock values = queue("q", context("1"), context(S, value)).block();

    assertThat(XsDecimal.parse(value)).isEmpty();
    assertThatThrownBy(() -> StatisticFunctions.sum(values))
        .isInstanceOf(QueueDataException.class)
        .hasMessage("sum: \"" + value + "\" is no xs:decimal");
    assertThatThrownBy(() -> StatisticFunctions.average(values))
        .isInstanceOf(QueueDataException.class)
        .hasMessage("average: \"" + value + "\" is no xs:decimal");
  }

  @Test
  void decimalReadsTheLexicalFormAroundXmlWhitespace() {
    assertThat(XsDecimal.parse(" \t\r\n-1.50\n")).contains(new BigDecimal("-1.50"));
    assertThat(XsDecimal.parse("+7.")).contains(new BigDecimal("7"));
    assertThat(XsDecimal.parse(".5")).contains(new BigDecimal("0.5"));
  }

  // --- interruption (ADR-0021) ---

  static Stream<Arguments> interruptible() {
    QueueBlock values = queue("values", context("1", S), context("2")).block();
    return Stream.of(
        Arguments.of("sum", (Supplier<QueueBlock>) () -> StatisticFunctions.sum(values)),
        Arguments.of("average", (Supplier<QueueBlock>) () -> StatisticFunctions.average(values)),
        Arguments.of("count", (Supplier<QueueBlock>) () -> StatisticFunctions.count(values)),
        Arguments.of(
            "index", (Supplier<QueueBlock>) () -> StatisticFunctions.index(values, ONE, ONE, true)),
        Arguments.of(
            "counter", (Supplier<QueueBlock>) () -> StatisticFunctions.counter(values, ONE, ONE)));
  }

  /**
   * Each function checks before each context, with its own name in the message: the block it builds
   * checks too, with another message, so the name tells the function's check.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptible")
  void onAnInterruptedThreadAFunctionStopsBeforeAContext(
      String function, Supplier<QueueBlock> call) {
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(call.get()).isNotNull();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(call::get)
          .isInstanceOf(CancellationException.class)
          .hasMessage(function + " was running when the thread was interrupted");
    } finally {
      Thread.interrupted();
    }
  }
}
