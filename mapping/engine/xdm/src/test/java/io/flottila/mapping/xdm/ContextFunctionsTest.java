package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
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
 * The context functions of the runtime library over queue blocks (ADR-0025, ADR-0004), at {@code
 * ContextFunctions}. Property tests (ADR-0018): each runs over hand-picked edge queues and over 200
 * seeded random queues, the seed in the case name, against a list-of-contexts reference written
 * here. No SUPPRESS yet (#51), except for {@code replaceValue} (#216, F-45), which reads SUPPRESS
 * and so is a context function; its tests use the SUPPRESS queues of {@link SuppressQueues}.
 *
 * <p>{@code splitByValue} (#55, owner decision Q3 of #48) splits each context of its input on its
 * own and keeps the existing context boundaries; an empty context stays one empty context. Its
 * three modes are three functions here, because the runtime binds the mode once per call site.
 */
class ContextFunctionsTest {

  /**
   * A queue as its contexts of values, the reference the block is built from and read against.
   *
   * @param name the case name: the edge it crosses, or its seed
   * @param contexts the values of each context, in order
   */
  record Queue(String name, List<List<String>> contexts) {

    /** Every value, context after context: what one context holding the whole queue holds. */
    List<String> flat() {
      List<String> all = new ArrayList<>();
      contexts.forEach(all::addAll);
      return all;
    }

    /** A fresh values array, as a builder of this queue hands it over. */
    String[] values() {
      return flat().toArray(String[]::new);
    }

    /** Fresh context starts with the end sentinel (ADR-0004). */
    int[] starts() {
      int[] starts = new int[contexts.size() + 1];
      for (int c = 0; c < contexts.size(); c++) {
        starts[c + 1] = starts[c] + contexts.get(c).size();
      }
      return starts;
    }

    QueueBlock block() {
      return QueueBlock.of(values(), starts());
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** Values a source queue holds: {@code ""} is a value, and whitespace is part of it. */
  private static final String[] SAMPLE = {"", "a", " b ", "1", "Grüße", "x\ty"};

  /** The edges every property crosses on every run, whatever the seeds give. */
  private static Stream<Queue> edges() {
    return Stream.of(
        // no context at all, {0}: a function result can be one, a source queue never (ADR-0004)
        new Queue("no context", List.of()),
        new Queue("one empty context", List.of(List.of())),
        new Queue("only empty contexts", List.of(List.of(), List.of(), List.of())),
        new Queue("one context", List.of(List.of("a", "b"))),
        // the worked example of fn/removeContexts/001: Sku in context Line over two Orders
        new Queue("a | b | c", List.of(List.of("a"), List.of("b"), List.of("c"))),
        // the worked example of fn/removeContexts/002: an empty context and the value ""
        new Queue(
            "a | (empty) | \"\" | b", List.of(List.of("a"), List.of(), List.of(""), List.of("b"))),
        new Queue("empty contexts at both ends", List.of(List.of(), List.of("x", "y"), List.of())),
        new Queue("one value", List.of(List.of("a"))),
        new Queue("equal runs", List.of(List.of("a", "a", "b", "b", "b", "a"))),
        // the input of obs-context-functions ctx-3 (f3.xml): Tag a, a, <Tag/>, b, <Tag/>
        new Queue("a, a, \"\", b, \"\"", List.of(List.of("a", "a", "", "b", ""))),
        new Queue("\"\" at both ends", List.of(List.of("", "a", ""), List.of("b"))),
        new Queue("only \"\"", List.of(List.of(""), List.of("", ""))),
        // a run across a context boundary stays split at the boundary
        new Queue("a run across contexts", List.of(List.of("a", "a"), List.of("a", "b"))));
  }

  /**
   * A seeded random queue. Some queues have many contexts and some contexts many slots; empty
   * contexts and queues without contexts come up often.
   */
  private static Queue seeded(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    List<List<String>> contexts = new ArrayList<>();
    int count = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(5);
    for (int c = 0; c < count; c++) {
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(4);
      for (int k = 0; k < slots; k++) {
        context.add(SAMPLE[random.nextInt(SAMPLE.length)]);
      }
      contexts.add(context);
    }
    return new Queue("seed " + seed, contexts);
  }

  static Stream<Arguments> queues() {
    return Stream.concat(
            edges(), LongStream.rangeClosed(1, 200).mapToObj(ContextFunctionsTest::seeded))
        .map(q -> Arguments.of(q.name(), q));
  }

  /** The queues with at least one slot, so that a values array exists to be shared. */
  static Stream<Arguments> queuesWithSlots() {
    return queues().filter(a -> !((Queue) a.get()[1]).flat().isEmpty());
  }

  /** The block as contexts of values, checking ADR-0004's invariants on the way. */
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

  private static List<String> slots(QueueBlock block) {
    List<String> values = new ArrayList<>();
    for (int i = 0; i < block.slots(); i++) {
      values.add(block.value(i));
    }
    return values;
  }

  /** Every slot of every context, in order, and nothing else; the input reads as before. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void removeContextsKeepsEverySlotInOrder(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.removeContexts(input);

    assertThat(slots(result)).containsExactlyElementsOf(queue.flat());
    // a block is immutable: the call leaves its input as it was
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  /**
   * Exactly one context, holding the whole queue. A queue with no context at all gives one empty
   * context, {@code {0, 0}}, not none: the same as reading the field in the root's context.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void removeContextsGivesOneContext(String name, Queue queue) {
    QueueBlock result = ContextFunctions.removeContexts(queue.block());

    assertThat(result.contexts()).isEqualTo(1);
    assertThat(read(result)).containsExactly(queue.flat());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void removeContextsIsIdempotent(String name, Queue queue) {
    QueueBlock once = ContextFunctions.removeContexts(queue.block());

    QueueBlock twice = ContextFunctions.removeContexts(once);

    assertThat(read(twice)).isEqualTo(read(once));
  }

  /**
   * ADR-0004: removeContexts shares the value array, it copies neither values nor characters. A
   * block takes the arrays it is given without copying them, so the only way to see the sharing
   * from outside is to break the hand-over rule on purpose: write the array after the call, and the
   * result reads the new values.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queuesWithSlots")
  void removeContextsSharesTheValueArray(String name, Queue queue) {
    String[] values = queue.values();
    QueueBlock input = QueueBlock.of(values, queue.starts());

    QueueBlock result = ContextFunctions.removeContexts(input);
    // witness: before the write the result holds the input's own value objects
    for (int i = 0; i < values.length; i++) {
      assertThat(result.value(i)).as("slot index %d", i).isSameAs(input.value(i));
    }
    List<String> written = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      values[i] = "written " + i;
      written.add(values[i]);
    }

    assertThat(slots(result)).containsExactlyElementsOf(written);
  }

  // --- splitByValue (#55): references over contexts of values

  /** Each value in a context of its own; an empty context stays one. */
  private static List<List<String>> eachValue(Queue queue) {
    List<List<String>> contexts = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      if (context.isEmpty()) {
        contexts.add(List.of());
      }
      for (String value : context) {
        contexts.add(List.of(value));
      }
    }
    return contexts;
  }

  /** A new context at each value that differs from the one before it in its context. */
  private static List<List<String>> valueChange(Queue queue) {
    List<List<String>> contexts = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      List<String> run = new ArrayList<>();
      for (String value : context) {
        if (!run.isEmpty() && !run.getLast().equals(value)) {
          contexts.add(run);
          run = new ArrayList<>();
        }
        run.add(value);
      }
      contexts.add(run);
    }
    return contexts;
  }

  /** Each {@code ""} ends its context there and starts a new one, and is dropped. */
  private static List<List<String>> emptyValue(Queue queue) {
    List<List<String>> contexts = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      List<String> part = new ArrayList<>();
      for (String value : context) {
        if (value.isEmpty()) {
          contexts.add(part);
          part = new ArrayList<>();
        } else {
          part.add(value);
        }
      }
      contexts.add(part);
    }
    return contexts;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void splitByEachValuePutsEveryValueInAContextOfItsOwn(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.splitByEachValue(input);

    assertThat(read(result)).isEqualTo(eachValue(queue));
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void splitByValueChangeStartsAContextWhereTheValueChanges(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.splitByValueChange(input);

    assertThat(read(result)).isEqualTo(valueChange(queue));
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void splitByEmptyValueSplitsAtEachEmptyStringAndDropsIt(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.splitByEmptyValue(input);

    assertThat(read(result)).isEqualTo(emptyValue(queue));
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  /**
   * The three modes over the input of obs-context-functions ctx-3, written out by hand, so that the
   * references above are checked against values they did not compute.
   */
  @Test
  void theThreeModesOverTheObservationInput() {
    QueueBlock tags = QueueBlock.of(new String[] {"a", "a", "", "b", ""}, new int[] {0, 5});

    assertThat(read(ContextFunctions.splitByEachValue(tags)))
        .containsExactly(List.of("a"), List.of("a"), List.of(""), List.of("b"), List.of(""));
    assertThat(read(ContextFunctions.splitByValueChange(tags)))
        .containsExactly(List.of("a", "a"), List.of(""), List.of("b"), List.of(""));
    assertThat(read(ContextFunctions.splitByEmptyValue(tags)))
        .containsExactly(List.of("a", "a"), List.of("b"), List.of());
  }

  /**
   * ADR-0004: eachValue and valueChange only move context boundaries, so they share the values
   * array as removeContexts does, seen from outside by writing the array after the call.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queuesWithSlots")
  void splitByEachValueAndValueChangeShareTheValueArray(String name, Queue queue) {
    String[] values = queue.values();
    QueueBlock input = QueueBlock.of(values, queue.starts());

    QueueBlock each = ContextFunctions.splitByEachValue(input);
    QueueBlock change = ContextFunctions.splitByValueChange(input);
    List<String> written = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      values[i] = "written " + i;
      written.add(values[i]);
    }

    assertThat(slots(each)).containsExactlyElementsOf(written);
    assertThat(slots(change)).containsExactlyElementsOf(written);
  }

  /**
   * emptyValue drops values, so it cannot share the values array; its own array holds the input's
   * value objects, so no characters are copied (ADR-0004).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queuesWithSlots")
  void splitByEmptyValueKeepsTheInputsValueObjects(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.splitByEmptyValue(input);

    int kept = 0;
    for (int i = 0; i < input.slots(); i++) {
      if (!input.value(i).isEmpty()) {
        assertThat(result.value(kept++)).as("slot index %d", i).isSameAs(input.value(i));
      }
    }
    assertThat(result.slots()).isEqualTo(kept);
  }

  // --- collapseContexts (#56): the first value of each context, "" for an empty one

  /**
   * The reference: one context, holding each context's first value, or "" when it has none; none at
   * all when the queue holds no value and at most one context (F-47).
   */
  private static List<String> firstOfEach(Queue queue) {
    List<String> firsts = new ArrayList<>();
    if (queue.flat().isEmpty() && queue.contexts().size() <= 1) {
      return firsts;
    }
    for (List<String> context : queue.contexts()) {
      firsts.add(context.isEmpty() ? "" : context.getFirst());
    }
    return firsts;
  }

  /**
   * One context with one slot per input context: its first value, and {@code ""} for an empty
   * context. A queue whose one context is empty, or that has no context at all, gives one empty
   * context: PO holds such a queue as nothing, and its collapseContexts writes no value for it
   * (fn/collapseContexts/003, observed, F-47).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void collapseContextsKeepsTheFirstValueOfEachContextInOne(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.collapseContexts(input);

    assertThat(read(result)).containsExactly(firstOfEach(queue));
    // a block is immutable: the call leaves its input as it was
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  /** ADR-0004: collapseContexts copies references only, never characters. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queuesWithSlots")
  void collapseContextsCopiesReferencesToTheFirstValues(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.collapseContexts(input);

    for (int c = 0; c < input.contexts(); c++) {
      if (input.end(c) > input.start(c)) {
        assertThat(result.value(c)).as("context index %d", c).isSameAs(input.value(input.start(c)));
      }
    }
  }

  // --- useOneAsMany (#56): the one value of context k of the first queue, once per slot of context
  // k of the second, in the contexts of the third (F-48)

  /**
   * Three queues useOneAsMany pairs: {@code value} with as many contexts as {@code occurrences},
   * one value in each, and {@code contexts} with as many slots as {@code occurrences}.
   */
  record Triple(String name, Queue value, Queue occurrences, Queue contexts) {

    /**
     * The reference: the value of context k repeated once per slot of context k of occurrences, in
     * order.
     */
    List<String> repeated() {
      List<String> values = value.flat();
      List<String> all = new ArrayList<>();
      for (int k = 0; k < occurrences.contexts().size(); k++) {
        for (int i = 0; i < occurrences.contexts().get(k).size(); i++) {
          all.add(values.get(k));
        }
      }
      return all;
    }

    /** The reference result: {@link #repeated()} cut into the context sizes of {@code contexts}. */
    List<List<String>> expected() {
      List<String> all = repeated();
      List<List<String>> result = new ArrayList<>();
      int next = 0;
      for (List<String> context : contexts.contexts()) {
        result.add(all.subList(next, next + context.size()));
        next += context.size();
      }
      return result;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static Queue queue(String name, List<List<String>> contexts) {
    return new Queue(name, contexts);
  }

  /** The edges every useOneAsMany property crosses, whatever the seeds give. */
  private static Stream<Triple> tripleEdges() {
    return Stream.of(
        // no context anywhere: an absent parent on every input
        new Triple("no context", queue("", List.of()), queue("", List.of()), queue("", List.of())),
        // the third queue alone decides the result's contexts, even when it holds no slot
        new Triple(
            "empty contexts only in the third",
            queue("", List.of()),
            queue("", List.of()),
            queue("", List.of(List.of(), List.of()))),
        // the SAP figure (#2 §4): external | internal | external, 3 | 2 | 1, contexts 2, 1, 2, 1
        new Triple(
            "SAP figure",
            queue("", List.of(List.of("external"), List.of("internal"), List.of("external"))),
            queue("", List.of(List.of("n", "n", "n"), List.of("n", "n"), List.of("n"))),
            queue("", List.of(List.of("i", "i"), List.of("i"), List.of("i", "i"), List.of("i")))),
        // a value whose context of the second queue is empty is repeated no time
        new Triple(
            "an empty context in the second",
            queue("", List.of(List.of("a"), List.of("b"))),
            queue("", List.of(List.of(), List.of("x"))),
            queue("", List.of(List.of("y")))),
        // obs-useoneasmany uoam-1 on u1.xml: A | B over s1, s2, s3 | s4 in t1, t2, t3 | t4
        new Triple(
            "uoam-1 on u1",
            queue("", List.of(List.of("A"), List.of("B"))),
            queue("", List.of(List.of("s1", "s2", "s3"), List.of("s4"))),
            queue("", List.of(List.of("t1", "t2", "t3"), List.of("t4")))));
  }

  /** A random partition of {@code values} into contexts, empty contexts included. */
  private static List<List<String>> partition(RandomGenerator random, List<String> values) {
    List<List<String>> contexts = new ArrayList<>();
    int next = 0;
    while (next < values.size() || random.nextInt(4) == 0) {
      int size = random.nextInt(Math.min(values.size() - next, 4) + 1);
      contexts.add(new ArrayList<>(values.subList(next, next + size)));
      next += size;
      if (contexts.size() > 60) {
        // the remaining values in one last context keep the partition finite
        contexts.add(new ArrayList<>(values.subList(next, values.size())));
        break;
      }
    }
    return contexts;
  }

  /**
   * A seeded random triple: the second queue is a seeded queue, the first holds one random value in
   * a context of its own per context of it, and the third re-partitions its slots.
   */
  private static Triple seededTriple(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    Queue occurrences = seeded(seed);
    List<List<String>> values = new ArrayList<>();
    for (int k = 0; k < occurrences.contexts().size(); k++) {
      values.add(List.of(SAMPLE[random.nextInt(SAMPLE.length)]));
    }
    return new Triple(
        "seed " + seed,
        queue("", values),
        occurrences,
        queue("", partition(random, occurrences.flat())));
  }

  static Stream<Arguments> triples() {
    return Stream.concat(
            tripleEdges(),
            LongStream.rangeClosed(1, 200).mapToObj(ContextFunctionsTest::seededTriple))
        .map(t -> Arguments.of(t.name(), t));
  }

  /** The repeated values in the third queue's contexts; no input changes. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("triples")
  void useOneAsManyRepeatsEachValueInTheContextsOfTheThird(String name, Triple triple) {
    QueueBlock value = triple.value().block();
    QueueBlock occurrences = triple.occurrences().block();
    QueueBlock contexts = triple.contexts().block();

    QueueBlock result = ContextFunctions.useOneAsMany(value, occurrences, contexts);

    assertThat(read(result)).isEqualTo(triple.expected());
    assertThat(read(value)).isEqualTo(triple.value().contexts());
    assertThat(read(occurrences)).isEqualTo(triple.occurrences().contexts());
    assertThat(read(contexts)).isEqualTo(triple.contexts().contexts());
  }

  /** ADR-0004: useOneAsMany copies references only: each slot is the first queue's value object. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("triples")
  void useOneAsManyCopiesReferencesToTheValues(String name, Triple triple) {
    QueueBlock value = triple.value().block();
    QueueBlock occurrences = triple.occurrences().block();

    QueueBlock result =
        ContextFunctions.useOneAsMany(value, occurrences, triple.contexts().block());

    int slot = 0;
    for (int k = 0; k < occurrences.contexts(); k++) {
      for (int i = occurrences.start(k); i < occurrences.end(k); i++) {
        assertThat(result.value(slot++)).as("slot index %d", i).isSameAs(value.value(k));
      }
    }
  }

  static Stream<Arguments> mismatches() {
    List<List<String>> two = List.of(List.of("x"), List.of("y", "z"));
    List<List<String>> fitted = List.of(List.of("a"), List.of("b"));
    List<List<String>> three = List.of(List.of("1", "2", "3"));
    return Stream.of(
        // the error conditions PO 7.50 and CPI share (F-48)
        Arguments.of(
            "fewer contexts in the first than in the second (fn/useOneAsMany/003)",
            queue("", List.of(List.of("a", "b"))),
            queue("", two),
            queue("", three),
            "the first has 1 contexts, the second 2"),
        Arguments.of(
            "more contexts in the first than in the second",
            queue("", List.of(List.of("a"), List.of("b"), List.of("c"))),
            queue("", two),
            queue("", three),
            "the first has 3 contexts, the second 2"),
        Arguments.of(
            "two values in a context of the first",
            queue("", List.of(List.of("a"), List.of("b", "c"))),
            queue("", two),
            queue("", three),
            "its context 2 has 2 values"),
        Arguments.of(
            "an empty context in the first (fn/useOneAsMany/008)",
            queue("", List.of(List.of("a"), List.of())),
            queue("", two),
            queue("", three),
            "its context 2 has 0 values"),
        Arguments.of(
            "an empty first context before a second of two values",
            queue("", List.of(List.of(), List.of("b", "c"))),
            queue("", two),
            queue("", three),
            "its context 1 has 0 values"),
        Arguments.of(
            "fewer slots in the third than in the second",
            queue("", fitted),
            queue("", two),
            queue("", List.of(List.of("1", "2"))),
            "2 slots"),
        Arguments.of(
            "more slots in the third than in the second",
            queue("", fitted),
            queue("", two),
            queue("", List.of(List.of("1", "2"), List.of("3", "4"))),
            "4 slots"),
        // the first input is checked before the third, as PO's message on the probe without
        // u4.xml's Order A names the first queue while the third is off too
        Arguments.of(
            "an empty context in the first and fewer slots in the third",
            queue("", List.of(List.of("a"), List.of())),
            queue("", two),
            queue("", List.of(List.of("1"))),
            "its context 2 has 0 values"));
  }

  /**
   * The error conditions PO 7.50 and CPI share (F-48): the first queue needs as many contexts as
   * the second and one value in each, and the third as many slots as the second, checked in this
   * order. The message names the count that is off.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("mismatches")
  void useOneAsManyRefusesMismatchedCounts(
      String why, Queue value, Queue occurrences, Queue contexts, String count) {
    // witness: the counts agree once the first and the third are fitted to the second
    Triple fitted =
        new Triple(
            why,
            queue("", List.of(List.of("a"), List.of("b"))),
            occurrences,
            queue("", List.of(occurrences.flat())));
    assertThat(
            read(
                ContextFunctions.useOneAsMany(
                    fitted.value().block(), occurrences.block(), fitted.contexts().block())))
        .isEqualTo(fitted.expected());

    assertThatThrownBy(
            () ->
                ContextFunctions.useOneAsMany(value.block(), occurrences.block(), contexts.block()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("useOneAsMany")
        .hasMessageContaining(count);
  }

  // --- replaceValue (#216, F-45): the replacement at every slot, SUPPRESS included

  /**
   * The observed example: the display queue of R1, replaceValue with 5 over createIf(Tag), in
   * obs-statistic sum-003 (po75/sum-003-stat-sum-suppress-queue.txt, PO 7.50 and CPI agree) shows
   * {@code ["", SUPPRESS, ""] [SUPPRESS] [] [""]} become {@code [5, 5, 5] [5] [] [5]}: each slot
   * the replacement, a SUPPRESS slot without its bit, and the contexts as they were, the empty one
   * empty. A queue with no context at all, {@code {0}}, stays one.
   */
  @Test
  void replaceValueReplacesEverySlotSuppressIncluded() {
    SuppressQueues.Queue observed =
        SuppressQueues.queue(
            "sum-003 R1",
            SuppressQueues.context("", SuppressQueues.S, ""),
            SuppressQueues.context(SuppressQueues.S),
            SuppressQueues.context(),
            SuppressQueues.context(""));
    QueueBlock input = observed.block();

    QueueBlock result = ContextFunctions.replaceValue(input, "5");

    assertThat(SuppressQueues.read(result))
        .containsExactly(List.of("5", "5", "5"), List.of("5"), List.of(), List.of("5"));
    assertThat(SuppressQueues.suppressedSlots(result))
        .containsExactly(false, false, false, false, false);
    // a block is immutable: the call leaves its input as it was
    assertThat(SuppressQueues.read(input)).isEqualTo(observed.contexts());

    QueueBlock none =
        ContextFunctions.replaceValue(SuppressQueues.queue("no context").block(), "5");

    assertThat(none.contexts()).isZero();
    assertThat(none.slots()).isZero();
  }

  static Stream<Arguments> suppressQueues() {
    return SuppressQueues.queues().map(q -> Arguments.of(q.name(), q));
  }

  /**
   * Over the SUPPRESS edges, SUPPRESS on both sides of a word of the bitset among them, and 200
   * seeded queues: as many contexts as the input, each with as many slots, each slot the
   * replacement and none SUPPRESS; the input reads as before.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("suppressQueues")
  void replaceValueKeepsEveryContextAndReplacesEachOfItsSlots(
      String name, SuppressQueues.Queue queue) {
    String replacement = "O'Brien & <Co>";
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.replaceValue(input, replacement);

    List<List<String>> replaced = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      replaced.add(Collections.nCopies(context.size(), replacement));
    }
    assertThat(SuppressQueues.read(result)).isEqualTo(replaced);
    assertThat(SuppressQueues.suppressedSlots(result)).doesNotContain(true);
    assertThat(SuppressQueues.read(input)).isEqualTo(queue.contexts());
  }

  // --- interruption (ADR-0021): every loop over contexts or slots checks for it

  /**
   * Calls whose own loop checks first, so the exception names the function: building a block from
   * starts other than {@code {0}} loops over them and checks too, so useOneAsMany's third input has
   * no context, and the second one empty context its loop crosses; collapseContexts builds its
   * result from its values, which checks too, after its own loop.
   */
  static Stream<Arguments> interruptible() {
    QueueBlock queue = new Queue("", List.of(List.of("a"), List.of())).block();
    QueueBlock one = new Queue("", List.of(List.of("a"))).block();
    QueueBlock oneEmpty = new Queue("", List.of(List.of())).block();
    QueueBlock none = new Queue("", List.of()).block();
    return Stream.of(
        Arguments.of(
            "collapseContexts",
            (Supplier<QueueBlock>) () -> ContextFunctions.collapseContexts(queue)),
        Arguments.of(
            "useOneAsMany",
            (Supplier<QueueBlock>) () -> ContextFunctions.useOneAsMany(one, oneEmpty, none)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptible")
  void onAnInterruptedThreadAFunctionThrowsCancellationExceptionAndGivesNoBlock(
      String function, Supplier<QueueBlock> call) {
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(call.get()).isNotNull();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(call::get)
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining(function + " was running");
    } finally {
      Thread.interrupted();
    }
  }

  /**
   * splitByValue in each mode, over a queue with a context of two slots and an empty one: its loop
   * over contexts checks, and names the function, as building the result block checks too, but with
   * a message of its own (ADR-0021).
   */
  static Stream<Arguments> interruptibleSplits() {
    QueueBlock queue = new Queue("", List.of(List.of("a", ""), List.of())).block();
    return Stream.of(
        Arguments.of(
            "eachValue",
            (Supplier<QueueBlock>) () -> ContextFunctions.splitByEachValue(queue),
            List.of(List.of("a"), List.of(""), List.of())),
        Arguments.of(
            "valueChange",
            (Supplier<QueueBlock>) () -> ContextFunctions.splitByValueChange(queue),
            List.of(List.of("a"), List.of(""), List.of())),
        Arguments.of(
            "emptyValue",
            (Supplier<QueueBlock>) () -> ContextFunctions.splitByEmptyValue(queue),
            List.of(List.of("a"), List.of(), List.of())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptibleSplits")
  void onAnInterruptedThreadSplitByValueThrowsCancellationExceptionNamingItself(
      String mode, Supplier<QueueBlock> call, List<List<String>> split) {
    // witness: the same call splits the queue on a thread that is not interrupted
    assertThat(read(call.get())).isEqualTo(split);

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(call::get)
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining("splitByValue was running");
    } finally {
      Thread.interrupted();
    }
  }

  // --- formatByExample (#58): the values of the first queue in the contexts of the second (#2 §4)

  /**
   * {@code count} distinct values {@code v1} … in contexts of their own that the example does not
   * have: an empty context first, then one value per context, so that a result that kept them, or
   * counted the empty one, shows.
   */
  private static Queue valuesFor(int count) {
    List<List<String>> contexts = new ArrayList<>();
    contexts.add(List.of());
    for (int k = 1; k <= count; k++) {
      contexts.add(List.of("v" + k));
    }
    return new Queue(count + " values", contexts);
  }

  /** The reference: {@code values} in order, cut to the sizes of {@code example}'s contexts. */
  private static List<List<String>> formattedBy(List<String> values, Queue example) {
    List<List<String>> contexts = new ArrayList<>();
    int next = 0;
    for (List<String> context : example.contexts()) {
      contexts.add(values.subList(next, next + context.size()));
      next += context.size();
    }
    return contexts;
  }

  /**
   * Every value in order, in the example's contexts, empty ones and none at all included; the
   * contexts of the values play no part. Both inputs read as before.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void formatByExampleCutsTheValuesAtTheExamplesContextChanges(String name, Queue example) {
    Queue values = valuesFor(example.flat().size());
    QueueBlock valueBlock = values.block();
    QueueBlock exampleBlock = example.block();

    QueueBlock result = ContextFunctions.formatByExample(valueBlock, exampleBlock);

    assertThat(read(result)).isEqualTo(formattedBy(values.flat(), example));
    assertThat(read(valueBlock)).isEqualTo(values.contexts());
    assertThat(read(exampleBlock)).isEqualTo(example.contexts());
  }

  /** It changes only the boundaries, so it shares the values array as removeContexts does. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queuesWithSlots")
  void formatByExampleSharesTheValueArray(String name, Queue example) {
    String[] values = valuesFor(example.flat().size()).values();
    QueueBlock input = QueueBlock.of(values, valuesFor(values.length).starts());

    QueueBlock result = ContextFunctions.formatByExample(input, example.block());
    // witness: before the write the result holds the input's own value objects
    for (int i = 0; i < values.length; i++) {
      assertThat(result.value(i)).as("slot index %d", i).isSameAs(input.value(i));
    }
    values[0] = "written";

    assertThat(result.value(0)).isEqualTo("written");
  }

  /**
   * One value more, or one fewer, than the example holds is refused, whatever the contexts: the SAP
   * documentation requires equal value counts (#2 §4). The message names both counts.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void formatByExampleRefusesUnequalValueCounts(String name, Queue example) {
    int count = example.flat().size();
    QueueBlock exampleBlock = example.block();

    assertThatThrownBy(
            () -> ContextFunctions.formatByExample(valuesFor(count + 1).block(), exampleBlock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("formatByExample")
        .hasMessageContaining((count + 1) + " values")
        .hasMessageContaining(count + " values");
    if (count > 0) {
      assertThatThrownBy(
              () -> ContextFunctions.formatByExample(valuesFor(count - 1).block(), exampleBlock))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining((count - 1) + " values");
    }
  }
}
