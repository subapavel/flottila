package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.ContextFunctions.Comparison;
import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The context functions of #55 and #56 over queues that hold SUPPRESS (#51, ADR-0004). A reading
 * marked observed is PO 7.50's and CPI's (F-47); the others are assumed until an observed case
 * confirms them (#61):
 *
 * <ul>
 *   <li>{@code splitByValue} keeps each SUPPRESS as a slot in every mode; in mode valueChange a
 *       SUPPRESS equals another SUPPRESS and no value, as SAP's SUPPRESS string would compare, and
 *       in mode emptyValue a SUPPRESS is no {@code ""} (observed, fn/splitByValue/019 to 021);
 *   <li>{@code collapseContexts} keeps a SUPPRESS first slot as SUPPRESS (observed,
 *       obs-context-functions ctx-6b);
 *   <li>{@code useOneAsMany} repeats a SUPPRESS value as SUPPRESS and counts a SUPPRESS occurrence
 *       as one (observed, the probes of obs-useoneasmany, F-48), and takes only the boundaries of
 *       its third input;
 *   <li>{@code formatByExample} keeps a SUPPRESS of its values at its slot and counts a SUPPRESS of
 *       its example as a value;
 *   <li>{@code sort} and {@code sortByKey} take a SUPPRESS key for the greatest key in every
 *       comparison, so it comes last ascending and first descending (observed for a case-sensitive
 *       sort and an ascending sortByKey, fn/sort/012, fn/sort/013, fn/sortByKey/006), and a numeric
 *       sort does not parse it; a SUPPRESS value moves with its key and stays SUPPRESS. A numeric
 *       sortByKey key must not be SUPPRESS (#2 §3, documented).
 * </ul>
 *
 * Worked examples first, then property tests (ADR-0018) over the edges and 200 seeded queues of
 * {@link SuppressQueues}, against list references written here.
 */
class ContextFunctionsSuppressReadingsTest {

  static Stream<Arguments> queues() {
    return SuppressQueues.queues().map(q -> Arguments.of(q.name(), q));
  }

  /** {@code a} and {@code b} are the same queue value: equal strings, or both SUPPRESS. */
  private static boolean same(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }

  // --- splitByValue

  @Test
  void splitByValueChangeTakesSuppressForEqualToSuppressOnly() {
    Queue queue = queue("worked", context("a", S, S, "a", "", S), context(S), context());

    QueueBlock result = ContextFunctions.splitByValueChange(queue.block());

    assertThat(read(result))
        .containsExactly(
            context("a"),
            context(S, S),
            context("a"),
            context(""),
            context(S),
            context(S),
            context());
  }

  @Test
  void splitByEmptyValueKeepsSuppressAsASlot() {
    Queue queue = queue("worked", context("a", S, "", S, ""), context(S, ""));

    QueueBlock result = ContextFunctions.splitByEmptyValue(queue.block());

    // taking SUPPRESS for "" would cut there and drop it, leaving no SUPPRESS at all
    assertThat(read(result))
        .containsExactly(context("a", S), context(S), context(), context(S), context());
  }

  /** Every slot, SUPPRESS included, in a context of its own; an empty context stays one. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void splitByEachValuePutsEverySuppressInAContextOfItsOwn(String name, Queue queue) {
    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      if (context.isEmpty()) {
        expected.add(context());
      }
      for (String slot : context) {
        expected.add(Collections.singletonList(slot));
      }
    }

    assertThat(read(ContextFunctions.splitByEachValue(queue.block()))).isEqualTo(expected);
  }

  /** A new context where a slot is not the same value as the one before it in its context. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void splitByValueChangeSplitsWhereSuppressMeetsAValue(String name, Queue queue) {
    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      List<String> run = new ArrayList<>();
      for (int i = 0; i < context.size(); i++) {
        if (i > 0 && !same(context.get(i), context.get(i - 1))) {
          expected.add(run);
          run = new ArrayList<>();
        }
        run.add(context.get(i));
      }
      expected.add(run);
    }

    assertThat(read(ContextFunctions.splitByValueChange(queue.block()))).isEqualTo(expected);
  }

  /** Each {@code ""} cuts its context and is dropped; a SUPPRESS stays where it stands. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void splitByEmptyValueCutsAtEmptyStringsOnly(String name, Queue queue) {
    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      List<String> run = new ArrayList<>();
      for (String slot : context) {
        if ("".equals(slot)) {
          expected.add(run);
          run = new ArrayList<>();
        } else {
          run.add(slot);
        }
      }
      expected.add(run);
    }

    assertThat(read(ContextFunctions.splitByEmptyValue(queue.block()))).isEqualTo(expected);
  }

  // --- collapseContexts

  /**
   * obs-context-functions ctx-6b, observed on PO 7.50 and CPI (F-47): {@code 5 | SUPPRESS | 7}
   * collapses to {@code 5, SUPPRESS, 7}.
   */
  @Test
  void collapseContextsKeepsASuppressFirstSlotAsSuppress() {
    Queue ctx6b = queue("ctx-6b", context("5"), context(S), context("7"));
    // the first slot decides, whatever follows it; an empty context among others is ""
    Queue firsts = queue("firsts", context(S, "x"), context("y", S), context());

    assertThat(read(ContextFunctions.collapseContexts(ctx6b.block())))
        .containsExactly(context("5", S, "7"));
    assertThat(read(ContextFunctions.collapseContexts(firsts.block())))
        .containsExactly(context(S, "y", ""));
  }

  /**
   * Each context's first slot, SUPPRESS as SUPPRESS, and {@code ""} for an empty context, except
   * that a queue whose one context is empty, or that has none, gives no slot (F-47).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void collapseContextsKeepsEachFirstSlotSuppressIncluded(String name, Queue queue) {
    List<String> expected = new ArrayList<>();
    if (!queue.flat().isEmpty() || queue.contexts().size() > 1) {
      for (List<String> context : queue.contexts()) {
        expected.add(context.isEmpty() ? "" : context.getFirst());
      }
    }

    assertThat(read(ContextFunctions.collapseContexts(queue.block()))).containsExactly(expected);
  }

  // --- useOneAsMany

  /**
   * Value {@code a | SUPPRESS} over occurrences {@code x, y | SUPPRESS} in contexts {@code
   * SUPPRESS, 1 | 2}: {@code a} twice and SUPPRESS once, cut as the third input is.
   */
  @Test
  void useOneAsManyRepeatsASuppressValueAsSuppress() {
    QueueBlock value = queue("value", context("a"), context(S)).block();
    QueueBlock occurrences = queue("occurrences", context("x", "y"), context(S)).block();
    QueueBlock contexts = queue("contexts", context(S, "1"), context("2")).block();

    QueueBlock result = ContextFunctions.useOneAsMany(value, occurrences, contexts);

    assertThat(read(result)).containsExactly(context("a", "a"), context(S));
  }

  /** The inputs of one useOneAsMany whose counts agree. */
  record Triple(String name, Queue value, Queue occurrences, Queue contexts) {

    /**
     * The one value of context k once per slot of occurrence context k, cut at the context sizes of
     * the third.
     */
    List<List<String>> expected() {
      List<String> values = value.flat();
      List<String> repeated = new ArrayList<>();
      for (int k = 0; k < occurrences.contexts().size(); k++) {
        for (int n = 0; n < occurrences.contexts().get(k).size(); n++) {
          repeated.add(values.get(k));
        }
      }
      List<List<String>> expected = new ArrayList<>();
      int at = 0;
      for (List<String> context : contexts.contexts()) {
        expected.add(repeated.subList(at, at + context.size()));
        at += context.size();
      }
      return expected;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** {@code slots} cut into seeded contexts, empty ones among them, SUPPRESS kept. */
  private static List<List<String>> cut(List<String> slots, RandomGenerator random) {
    List<List<String>> contexts = new ArrayList<>();
    List<String> context = new ArrayList<>();
    for (String slot : slots) {
      while (random.nextInt(3) == 0) {
        contexts.add(context);
        context = new ArrayList<>();
      }
      context.add(slot);
    }
    contexts.add(context);
    return contexts;
  }

  /**
   * A seeded triple over a seeded occurrences queue: one value in a context of its own per context
   * of it, SUPPRESS among them (F-48), and a third input of as many slots, SUPPRESS among them too,
   * cut its own way.
   */
  private static Triple triple(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    Queue occurrences = SuppressQueues.seeded("seed " + seed + " occurrences", random);
    List<List<String>> values = new ArrayList<>();
    for (int k = 0; k < occurrences.contexts().size(); k++) {
      values.add(context(random.nextInt(3) == 0 ? S : "v" + k));
    }
    List<String> thirds = new ArrayList<>();
    for (int i = 0; i < occurrences.flat().size(); i++) {
      thirds.add(random.nextInt(4) == 0 ? S : "c" + i);
    }
    return new Triple(
        "seed " + seed,
        new Queue("value", values),
        occurrences,
        new Queue("contexts", cut(thirds, random)));
  }

  static Stream<Arguments> triples() {
    return SuppressQueues.seeds().mapToObj(seed -> Arguments.of("seed " + seed, triple(seed)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("triples")
  void useOneAsManyKeepsSuppressValuesAndCountsSuppressOccurrences(String name, Triple triple) {
    QueueBlock result =
        ContextFunctions.useOneAsMany(
            triple.value().block(), triple.occurrences().block(), triple.contexts().block());

    assertThat(read(result)).isEqualTo(triple.expected());
  }

  // --- formatByExample

  /**
   * Values {@code a, SUPPRESS | b} in the contexts of example {@code SUPPRESS | x, SUPPRESS}: the
   * example's SUPPRESS counts as a value, and the values' SUPPRESS stays at its slot.
   */
  @Test
  void formatByExampleKeepsSuppressValuesAndCountsSuppressInTheExample() {
    QueueBlock values = queue("values", context("a", S), context("b")).block();
    QueueBlock example = queue("example", context(S), context("x", S)).block();

    QueueBlock result = ContextFunctions.formatByExample(values, example);

    assertThat(read(result)).containsExactly(context("a"), context(S, "b"));
  }

  /** Every slot of the values, SUPPRESS kept, cut at a seeded example with SUPPRESS among it. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void formatByExampleCutsEverySlotAtTheExamplesContexts(String name, Queue queue) {
    RandomGenerator random = new SplittableRandom(name.hashCode());
    List<String> flat = queue.flat();
    List<String> examples = new ArrayList<>();
    for (int i = 0; i < flat.size(); i++) {
      examples.add(random.nextInt(3) == 0 ? S : "e" + i);
    }
    Queue example = new Queue("example", cut(examples, random));
    List<List<String>> expected = new ArrayList<>();
    int at = 0;
    for (List<String> context : example.contexts()) {
      expected.add(flat.subList(at, at + context.size()));
      at += context.size();
    }

    QueueBlock result = ContextFunctions.formatByExample(queue.block(), example.block());

    assertThat(read(result)).isEqualTo(expected);
  }

  // --- sort and sortByKey

  /**
   * fn/sort/012 and fn/sort/013, observed (F-47): SUPPRESS after {@code ""} ascending, before it
   * descending.
   */
  @Test
  void sortTakesSuppressForTheGreatestValueInEveryComparison() {
    QueueBlock words = queue("words", context("b", S, "A", "", S, "a"), context(S)).block();
    QueueBlock numbers = queue("numbers", context("10", S, " 2 ", S, "-1")).block();

    assertThat(read(ContextFunctions.sort(words, Comparison.LEXICOGRAPHIC, false)))
        .containsExactly(context("", "A", "a", "b", S, S), context(S));
    assertThat(read(ContextFunctions.sort(words, Comparison.LEXICOGRAPHIC, true)))
        .containsExactly(context(S, S, "b", "a", "A", ""), context(S));
    // "A" and "a" are equal keys here, so they keep their input order either way
    assertThat(read(ContextFunctions.sort(words, Comparison.CASE_INSENSITIVE, false)))
        .containsExactly(context("", "A", "a", "b", S, S), context(S));
    assertThat(read(ContextFunctions.sort(words, Comparison.CASE_INSENSITIVE, true)))
        .containsExactly(context(S, S, "b", "A", "a", ""), context(S));
    // a SUPPRESS is not parsed, so the numeric sort does not stop at it
    assertThat(read(ContextFunctions.sort(numbers, Comparison.NUMERIC, false)))
        .containsExactly(context("-1", " 2 ", "10", S, S));
    assertThat(read(ContextFunctions.sort(numbers, Comparison.NUMERIC, true)))
        .containsExactly(context(S, S, "10", " 2 ", "-1"));
  }

  /**
   * Keys {@code 2, SUPPRESS, 1, SUPPRESS} over values {@code w, x, SUPPRESS, z}: compared as
   * strings a SUPPRESS key is the greatest (fn/sortByKey/006, observed, F-47), equal SUPPRESS keys
   * keep their input order, and a SUPPRESS value moves with its key.
   */
  @Test
  void sortByKeyMovesSuppressValuesWithTheirKeys() {
    QueueBlock keys = queue("keys", context("2", S, "1", S)).block();
    QueueBlock values = queue("values", context("w", "x", S, "z")).block();

    assertThat(read(ContextFunctions.sortByKey(keys, values, Comparison.LEXICOGRAPHIC, false)))
        .containsExactly(context(S, "w", "x", "z"));
    assertThat(read(ContextFunctions.sortByKey(keys, values, Comparison.CASE_INSENSITIVE, true)))
        .containsExactly(context("x", "z", "w", S));
  }

  /**
   * #2 §3: "numeric keys must not be equal to the constant ResultList.SUPPRESS". A numeric
   * sortByKey over a SUPPRESS key stops, where a numeric sort takes it for the greatest value.
   */
  @Test
  void aNumericSortByKeyOverASuppressKeyStops() {
    QueueBlock keys = queue("keys", context("2", S, "1")).block();
    QueueBlock numbers = queue("numbers", context("2", "3", "1")).block();
    QueueBlock values = queue("values", context("w", S, "z")).block();
    // witness: numeric keys without SUPPRESS sort the same values, their SUPPRESS kept
    assertThat(read(ContextFunctions.sortByKey(numbers, values, Comparison.NUMERIC, false)))
        .containsExactly(context("z", "w", S));
    assertThat(read(ContextFunctions.sort(keys, Comparison.NUMERIC, false)))
        .containsExactly(context("1", "2", S));

    assertThatThrownBy(() -> ContextFunctions.sortByKey(keys, values, Comparison.NUMERIC, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sortByKey")
        .hasMessageContaining("SUPPRESS");
  }

  /**
   * {@code context} stably sorted by {@code keys} under {@code order}, a null key last, reversed
   * when {@code descending}: the reference of a sort.
   */
  private static <K> List<String> sortedBy(
      List<K> keys, List<String> context, Comparator<K> order, boolean descending) {
    Comparator<Integer> byKey = Comparator.comparing(keys::get, Comparator.nullsLast(order));
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < context.size(); i++) {
      slots.add(i);
    }
    slots.sort(descending ? byKey.reversed() : byKey);
    return slots.stream().map(context::get).toList();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void sortOrdersSuppressLastAscendingAndFirstDescending(String name, Queue queue) {
    for (boolean descending : new boolean[] {false, true}) {
      List<List<String>> lexicographic = new ArrayList<>();
      List<List<String>> caseInsensitive = new ArrayList<>();
      for (List<String> context : queue.contexts()) {
        lexicographic.add(sortedBy(context, context, String::compareTo, descending));
        caseInsensitive.add(sortedBy(context, context, String::compareToIgnoreCase, descending));
      }

      assertThat(read(ContextFunctions.sort(queue.block(), Comparison.LEXICOGRAPHIC, descending)))
          .as("lexicographic, descending %s", descending)
          .isEqualTo(lexicographic);
      assertThat(
              read(ContextFunctions.sort(queue.block(), Comparison.CASE_INSENSITIVE, descending)))
          .as("caseInsensitive, descending %s", descending)
          .isEqualTo(caseInsensitive);
    }
  }

  /**
   * Numeric keys in place of the queue's slots, some equal as numbers but not as strings ({@code 1}
   * and {@code 1.0}), over values that are the slot indexes with a seeded SUPPRESS among them: the
   * order of equal keys shows the sort is stable, and each SUPPRESS value moves with its key.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void sortByKeyNumericKeepsSuppressValuesWithTheirKeys(String name, Queue queue) {
    RandomGenerator random = new SplittableRandom(name.hashCode());
    List<List<String>> keys = new ArrayList<>();
    List<List<String>> values = new ArrayList<>();
    int slot = 0;
    for (List<String> context : queue.contexts()) {
      List<String> contextKeys = new ArrayList<>();
      List<String> contextValues = new ArrayList<>();
      for (int k = 0; k < context.size(); k++) {
        int number = random.nextInt(5) - 2;
        contextKeys.add(random.nextBoolean() ? number + "" : number + ".0");
        contextValues.add(random.nextInt(4) == 0 ? S : "v" + slot++);
      }
      keys.add(contextKeys);
      values.add(contextValues);
    }
    QueueBlock keyBlock = new Queue("keys", keys).block();
    QueueBlock valueBlock = new Queue("values", values).block();

    for (boolean descending : new boolean[] {false, true}) {
      List<List<String>> expected = new ArrayList<>();
      for (int c = 0; c < keys.size(); c++) {
        List<BigDecimal> numbers = keys.get(c).stream().map(BigDecimal::new).toList();
        expected.add(sortedBy(numbers, values.get(c), BigDecimal::compareTo, descending));
      }

      assertThat(
              read(
                  ContextFunctions.sortByKey(keyBlock, valueBlock, Comparison.NUMERIC, descending)))
          .as("descending %s", descending)
          .isEqualTo(expected);
    }
  }
}
