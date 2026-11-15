package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.ContextFunctions.Comparison;
import io.flottila.mapping.xdm.ContextFunctionsTest.Queue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code sort} and {@code sortByKey} (#59, owner decision Q4 of #48) at {@code ContextFunctions}:
 * each context on its own, a stable sort, lexicographic as {@link String#compareTo} (by UTF-16 code
 * unit), case-insensitive as {@link String#compareToIgnoreCase}, numeric by decimal value over
 * ADR-0005's {@code xs:decimal} lexical form; descending reverses the order, not the ties. Property
 * tests (ADR-0018) over seeded random queues, the seed in the case name, against the comparators
 * written here; {@code sortByKey} with each slot's index as its value shows where every slot went.
 * No SUPPRESS yet (#51).
 */
class ContextFunctionsSortTest {

  /** Values that cross the collation edges: case, digits against letters, beyond ASCII and BMP. */
  private static final String[] TEXT = {
    "", "a", "A", "b", "B", "_", "10", "9", "é", "e", "Z", "😀", "Ａ", "ﬀ", "ab"
  };

  /** Numbers in the xs:decimal lexical form, with equal values spelled differently. */
  private static final String[] NUMBERS = {
    "10", "9", "-1", "1.50", "1.5", " 2 ", "+0.5", ".25", "007", "7", "1.", "0", "-0", "\t3\n"
  };

  /** The reference comparison of keys, written here from #48 Q4 and ADR-0005. */
  private static Comparator<String> reference(Comparison comparison) {
    return switch (comparison) {
      case LEXICOGRAPHIC -> String::compareTo;
      case CASE_INSENSITIVE -> String::compareToIgnoreCase;
      case NUMERIC -> Comparator.comparing(v -> new BigDecimal(v.trim()));
    };
  }

  private static Queue seeded(long seed, String[] sample) {
    RandomGenerator random = new SplittableRandom(seed);
    List<List<String>> contexts = new ArrayList<>();
    int count = random.nextInt(6) == 0 ? random.nextInt(20) : random.nextInt(4);
    for (int c = 0; c < count; c++) {
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(6) == 0 ? random.nextInt(30) : random.nextInt(6);
      for (int k = 0; k < slots; k++) {
        context.add(sample[random.nextInt(sample.length)]);
      }
      contexts.add(context);
    }
    return new Queue("seed " + seed, contexts);
  }

  static Stream<Arguments> sorts() {
    return Stream.of(Comparison.values())
        .flatMap(
            comparison ->
                Stream.of(false, true)
                    .flatMap(
                        descending ->
                            LongStream.rangeClosed(1, 60)
                                .mapToObj(
                                    seed ->
                                        seeded(
                                            seed,
                                            comparison == Comparison.NUMERIC ? NUMBERS : TEXT))
                                .map(
                                    q ->
                                        Arguments.of(
                                            comparison
                                                + (descending ? " descending " : " ascending ")
                                                + q.name(),
                                            q,
                                            comparison,
                                            descending))));
  }

  /** The block as contexts of values. */
  private static List<List<String>> read(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    for (int c = 0; c < block.contexts(); c++) {
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        context.add(block.value(i));
      }
      contexts.add(context);
    }
    return contexts;
  }

  /** The queue of each slot's index, in {@code queue}'s contexts. */
  private static Queue indexes(Queue queue) {
    List<List<String>> contexts = new ArrayList<>();
    int index = 0;
    for (List<String> context : queue.contexts()) {
      List<String> indexes = new ArrayList<>();
      for (int k = 0; k < context.size(); k++) {
        indexes.add(String.valueOf(index++));
      }
      contexts.add(indexes);
    }
    return new Queue(queue.name(), contexts);
  }

  /**
   * sortByKey with each slot's index as its value: every context keeps its slots and only its
   * slots; the keys of a context are in order, and equal keys keep their input order, in descending
   * order too.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("sorts")
  void sortByKeyIsAStableSortOfEachContextOnItsOwn(
      String name, Queue keys, Comparison comparison, boolean descending) {
    Queue indexes = indexes(keys);
    List<String> flat = keys.flat();
    Comparator<String> order = reference(comparison);

    List<List<String>> result =
        read(ContextFunctions.sortByKey(keys.block(), indexes.block(), comparison, descending));

    assertThat(result).hasSameSizeAs(indexes.contexts());
    for (int c = 0; c < result.size(); c++) {
      List<String> context = result.get(c);
      assertThat(context).containsExactlyInAnyOrderElementsOf(indexes.contexts().get(c));
      for (int k = 1; k < context.size(); k++) {
        int before = Integer.parseInt(context.get(k - 1));
        int after = Integer.parseInt(context.get(k));
        int compared = order.compare(flat.get(before), flat.get(after));
        if (descending) {
          compared = -compared;
        }
        assertThat(compared).as("context %d, slots %d then %d", c, before, after).isLessThan(1);
        if (compared == 0) {
          assertThat(before).as("equal keys keep their input order").isLessThan(after);
        }
      }
    }
    // a block is immutable: the call leaves its inputs as they were
    assertThat(read(keys.block())).isEqualTo(keys.contexts());
  }

  /** sort is sortByKey of a queue by itself: its values go where sortByKey puts their slots. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("sorts")
  void sortIsSortByKeyOfTheQueueByItself(
      String name, Queue queue, Comparison comparison, boolean descending) {
    List<String> flat = queue.flat();
    List<List<String>> expected = new ArrayList<>();
    for (List<String> slots :
        read(
            ContextFunctions.sortByKey(
                queue.block(), indexes(queue).block(), comparison, descending))) {
      expected.add(slots.stream().map(i -> flat.get(Integer.parseInt(i))).toList());
    }

    assertThat(read(ContextFunctions.sort(queue.block(), comparison, descending)))
        .isEqualTo(expected);
  }

  private static List<String> sorted(List<String> values, Comparison comparison, boolean desc) {
    return read(ContextFunctions.sort(new Queue("", List.of(values)).block(), comparison, desc))
        .getFirst();
  }

  /**
   * The collation edges of fn/sort/001 to 004: by UTF-16 code unit the high surrogate of U+1F600
   * comes before U+FB00 and U+FF21, where the codepoint collation puts it after; case-insensitive
   * compares code points after folding, and puts {@code _} before every letter.
   */
  @Test
  void theOrdersAcrossTheCollationEdges() {
    List<String> wide = List.of("Ａ", "😀", "z", "ﬀ");
    List<String> mixed = List.of("B", "b", "10", "9", "_", "a", "A", "é", "e", "Z");

    assertThat(sorted(wide, Comparison.LEXICOGRAPHIC, false)).containsExactly("z", "😀", "ﬀ", "Ａ");
    assertThat(sorted(wide, Comparison.CASE_INSENSITIVE, false))
        .containsExactly("z", "ﬀ", "Ａ", "😀");
    assertThat(sorted(mixed, Comparison.LEXICOGRAPHIC, false))
        .containsExactly("10", "9", "A", "B", "Z", "_", "a", "b", "e", "é");
    assertThat(sorted(mixed, Comparison.CASE_INSENSITIVE, false))
        .containsExactly("10", "9", "_", "a", "A", "B", "b", "e", "Z", "é");
    assertThat(sorted(mixed, Comparison.LEXICOGRAPHIC, true))
        .containsExactly("é", "e", "b", "a", "_", "Z", "B", "A", "9", "10");
  }

  /** Numeric keeps each value's spelling, and equal values in input order either way. */
  @Test
  void numericComparesDecimalValuesAndKeepsTheSpelling() {
    List<String> numbers = List.of("10", "9", "-1", "1.50", " 2 ", "1.5", "+0.5", ".25");

    assertThat(sorted(numbers, Comparison.NUMERIC, false))
        .containsExactly("-1", ".25", "+0.5", "1.50", "1.5", " 2 ", "9", "10");
    assertThat(sorted(numbers, Comparison.NUMERIC, true))
        .containsExactly("10", "9", " 2 ", "1.50", "1.5", "+0.5", ".25", "-1");
  }

  /** The xs:decimal lexical form, whitespace collapsed, as the export's cast reads it. */
  @ParameterizedTest(name = "\"{0}\"")
  @MethodSource("decimals")
  void numericReadsTheDecimalLexicalForm(String value, String same) {
    assertThat(sorted(List.of("9", value, same, "-9"), Comparison.NUMERIC, false))
        .containsExactly("-9", value, same, "9");
  }

  static Stream<Arguments> decimals() {
    return Stream.of(
        Arguments.of("+.5", "0.5"),
        Arguments.of("-.5", "-0.50"),
        Arguments.of("1.", "1"),
        Arguments.of(" 2 ", "2.0"),
        Arguments.of("\t3\r\n", "3"),
        Arguments.of("007", "7"),
        Arguments.of("-0", "0"));
  }

  /**
   * A value that is not in the xs:decimal lexical form (ADR-0005; po:SORT in the export) stops a
   * numeric sort, even alone in its context, where nothing is compared. A lexicographic sort of the
   * same value is fine.
   */
  @ParameterizedTest(name = "\"{0}\"")
  @MethodSource("nonNumbers")
  void aNonNumberStopsANumericSort(String value) {
    QueueBlock queue = new Queue("", List.of(List.of("1"), List.of(value))).block();
    // witness: the comparison is what refuses it
    assertThat(read(ContextFunctions.sort(queue, Comparison.LEXICOGRAPHIC, false)))
        .containsExactly(List.of("1"), List.of(value));

    assertThatThrownBy(() -> ContextFunctions.sort(queue, Comparison.NUMERIC, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sort")
        .hasMessageContaining("\"" + value + "\"");
  }

  static Stream<String> nonNumbers() {
    // U+0661 is ARABIC-INDIC DIGIT ONE, which BigDecimal would read as 1
    return Stream.of("", " ", ".", "+", "-", "1e3", "1.2.3", "١", "0x10", "NaN", "1 2", "a");
  }

  /** sortByKey parses only its keys: values that are no numbers follow their keys. */
  @Test
  void sortByKeyParsesOnlyTheKeys() {
    QueueBlock keys = new Queue("", List.of(List.of("10", "9", "10.0", "-2"))).block();
    QueueBlock values = new Queue("", List.of(List.of("a", "b", "c", "d"))).block();

    assertThat(read(ContextFunctions.sortByKey(keys, values, Comparison.NUMERIC, true)))
        .containsExactly(List.of("a", "c", "b", "d"));
    assertThatThrownBy(() -> ContextFunctions.sortByKey(values, keys, Comparison.NUMERIC, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sortByKey")
        .hasMessageContaining("\"a\"");
  }

  static Stream<Arguments> mismatches() {
    return Stream.of(
        Arguments.of(
            "fewer contexts of keys",
            List.of(List.of("a")),
            List.of(List.of("x"), List.of()),
            "contexts of keys: 1, of values: 2"),
        Arguments.of(
            "more contexts of keys",
            List.of(List.of("a"), List.of()),
            List.of(List.of("x")),
            "contexts of keys: 2, of values: 1"),
        Arguments.of(
            "the same total, split otherwise",
            List.of(List.of("a", "b"), List.of("c")),
            List.of(List.of("x"), List.of("y", "z")),
            "in context 1 keys: 2, values: 1"),
        Arguments.of(
            "a key short in the last context",
            List.of(List.of("a"), List.of("b")),
            List.of(List.of("x"), List.of("y", "z")),
            "in context 2 keys: 1, values: 2"));
  }

  /**
   * A key/value count mismatch stops sortByKey (#2 §4, documented): the two queues need the same
   * contexts with the same number of slots each. The message names where they part. It is a data
   * error, a {@code QueueDataException}, which the runtime raises as {@code po:SORTBYKEY} at the
   * box (ADR-0025 since #52).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("mismatches")
  void sortByKeyRefusesAKeyValueCountMismatch(
      String why, List<List<String>> keys, List<List<String>> values, String where) {
    QueueBlock keyBlock = new Queue("", keys).block();
    // witness: the keys sort their own queue
    assertThat(
            read(ContextFunctions.sortByKey(keyBlock, keyBlock, Comparison.LEXICOGRAPHIC, false)))
        .isEqualTo(keys);

    assertThatThrownBy(
            () ->
                ContextFunctions.sortByKey(
                    keyBlock, new Queue("", values).block(), Comparison.LEXICOGRAPHIC, false))
        .isInstanceOf(QueueDataException.class)
        .hasMessageContaining("sortByKey")
        .hasMessageContaining(where);
  }

  /**
   * One interrupt check per context, which names the function: building the result block checks
   * too, but with a message of its own.
   */
  static Stream<Arguments> interruptible() {
    QueueBlock queue = new Queue("", List.of(List.of("b", "a"))).block();
    return Stream.of(
        Arguments.of(
            "sort was running",
            (Supplier<QueueBlock>)
                () -> ContextFunctions.sort(queue, Comparison.LEXICOGRAPHIC, false)),
        Arguments.of(
            "sortByKey was running",
            (Supplier<QueueBlock>)
                () -> ContextFunctions.sortByKey(queue, queue, Comparison.LEXICOGRAPHIC, false)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptible")
  void onAnInterruptedThreadASortThrowsCancellationExceptionNamingItself(
      String message, Supplier<QueueBlock> call) {
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(read(call.get())).containsExactly(List.of("a", "b"));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(call::get)
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining(message);
    } finally {
      Thread.interrupted();
    }
  }
}
