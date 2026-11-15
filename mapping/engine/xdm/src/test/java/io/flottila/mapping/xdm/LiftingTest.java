package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code Lifting.lift(inputs, body)}: a single-value body applied to queue blocks slot by slot, the
 * semantics of {@code Lift} and of the export's {@code po:lift} (ADR-0006):
 *
 * <ul>
 *   <li>context i of the result pairs context i of every input, and the result has as many contexts
 *       as the input with the most;
 *   <li>if any input's context i is empty or missing, result context i is empty;
 *   <li>otherwise the longest input sets the slot count, and a shorter input reuses its last value
 *       inside the context;
 *   <li>SUPPRESS in any input gives a SUPPRESS slot without running the body; the other inputs are
 *       still consumed. A reused SUPPRESS gives SUPPRESS (an assumed reading since #51, #61).
 * </ul>
 *
 * The body gets one value per input, in input order, as slot variable k reads input k. Property
 * tests (ADR-0018) run over edges and 200 seeded input lists, whose values are tokens unique to
 * their input, context and slot, so a value read from the wrong place shows.
 */
class LiftingTest {

  /** The body under its own name: the values in input order, joined by {@code +}. */
  private static final Function<String[], String> CONCAT = values -> String.join("+", values);

  /** {@code Lifting.lift} with {@code body} as whatever functional interface it takes. */
  private static QueueBlock lift(List<QueueBlock> inputs, Function<String[], String> body) {
    return Lifting.lift(inputs, body::apply);
  }

  /** The inputs of one lift, as reference queues. */
  record Inputs(String name, List<Queue> queues) {

    List<QueueBlock> blocks() {
      return queues.stream().map(Queue::block).toList();
    }

    /** Context {@code i} of input {@code k}, or null when the input has no such context. */
    List<String> context(int k, int i) {
      List<List<String>> contexts = queues.get(k).contexts();
      return i < contexts.size() ? contexts.get(i) : null;
    }

    /** Whether context {@code i} of some input is empty or missing. */
    boolean emptyOrMissing(int i) {
      for (int k = 0; k < queues.size(); k++) {
        List<String> context = context(k, i);
        if (context == null || context.isEmpty()) {
          return true;
        }
      }
      return false;
    }

    /** The slot count of the longest input's context {@code i}. */
    int longest(int i) {
      int longest = 0;
      for (int k = 0; k < queues.size(); k++) {
        longest = Math.max(longest, context(k, i).size());
      }
      return longest;
    }

    /**
     * Input {@code k}'s value at slot {@code j} of context {@code i}, its last one past its end.
     */
    String reused(int k, int i, int j) {
      List<String> context = context(k, i);
      return context.get(Math.min(j, context.size() - 1));
    }

    @Override
    public String toString() {
      return name;
    }
  }

  @SafeVarargs
  private static Inputs inputs(String name, List<List<String>>... queues) {
    List<Queue> list = new ArrayList<>();
    for (int k = 0; k < queues.length; k++) {
      list.add(new Queue(name + " in" + (k + 1), queues[k]));
    }
    return new Inputs(name, list);
  }

  /** The edges every property crosses on every run, whatever the seeds give. */
  private static Stream<Inputs> edges() {
    return Stream.of(
        inputs("one input without contexts", List.of()),
        inputs("two inputs without contexts", List.of(), List.of()),
        inputs("one input with one empty context", List.of(context())),
        // missing contexts: the second input has more
        inputs(
            "a | b against x | y | z",
            List.of(context("a"), context("b")),
            List.of(context("x"), context("y"), context("z"))),
        // an empty context in one input empties the result's, whatever the other holds
        inputs(
            "a | (empty) | c against x | y | z",
            List.of(context("a"), context(), context("c")),
            List.of(context("x"), context("y", "y2"), context("z"))),
        // the figure of #2 §1.1: reuse stays inside the context
        inputs(
            "1,2,3 | 4,5,6 against a,b | c,d",
            List.of(context("1", "2", "3"), context("4", "5", "6")),
            List.of(context("a", "b"), context("c", "d"))),
        // obs-lift-reuse lift-2: SUPPRESS consumes the other input's value
        inputs(
            "a5, SUPPRESS, c7 against t1, t2, t3",
            List.of(context("a5", S, "c7")),
            List.of(context("t1", "t2", "t3"))),
        // obs-lift-reuse lift-3 o2: the last value read is SUPPRESS and is reused
        inputs(
            "5, SUPPRESS against t1, t2, t3",
            List.of(context("5", S)),
            List.of(context("t1", "t2", "t3"))),
        // obs-lift-reuse lift-3 o1: a single SUPPRESS reused for every slot
        inputs(
            "SUPPRESS against t1, t2, t3", List.of(context(S)), List.of(context("t1", "t2", "t3"))),
        inputs(
            "three inputs of lengths 1, 2 and 3",
            List.of(context("a")),
            List.of(context("x", "y")),
            List.of(context("p", "q", "r"))));
  }

  /**
   * Seeded inputs: one to three, whose context counts are often equal and sometimes not; empty
   * contexts and SUPPRESS come up often. Each value is {@code q<input>c<context>s<slot>}.
   */
  private static Inputs seeded(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    int count = 1 + random.nextInt(3);
    int base = random.nextInt(8) == 0 ? random.nextInt(20) : random.nextInt(4);
    List<List<List<String>>> queues = new ArrayList<>();
    for (int q = 0; q < count; q++) {
      int contexts = random.nextInt(3) == 0 ? random.nextInt(base + 3) : base;
      List<List<String>> queue = new ArrayList<>();
      for (int c = 0; c < contexts; c++) {
        int slots = random.nextInt(8) == 0 ? random.nextInt(12) : random.nextInt(4);
        List<String> context = new ArrayList<>();
        for (int s = 0; s < slots; s++) {
          context.add(random.nextInt(6) == 0 ? S : "q" + q + "c" + c + "s" + s);
        }
        queue.add(Collections.unmodifiableList(context));
      }
      queues.add(List.copyOf(queue));
    }
    List<Queue> list = new ArrayList<>();
    for (int q = 0; q < count; q++) {
      list.add(new Queue("seed " + seed + " in" + (q + 1), queues.get(q)));
    }
    return new Inputs("seed " + seed, list);
  }

  static Stream<Arguments> inputLists() {
    return Stream.concat(edges(), SuppressQueues.seeds().mapToObj(LiftingTest::seeded))
        .map(in -> Arguments.of(in.name(), in));
  }

  static Stream<Arguments> queues() {
    return SuppressQueues.queues().map(q -> Arguments.of(q.name(), q));
  }

  /** The body that gives its one value back: a one-input lift reads as its input, SUPPRESS kept. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void liftIdentityBodyGivesItsInput(String name, Queue queue) {
    QueueBlock result = lift(List.of(queue.block()), values -> values[0]);

    assertThat(read(result)).isEqualTo(queue.contexts());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inputLists")
  void liftHasTheMostContextsOfItsInputs(String name, Inputs inputs) {
    int most = inputs.queues().stream().mapToInt(q -> q.contexts().size()).max().orElseThrow();

    QueueBlock result = lift(inputs.blocks(), CONCAT);

    assertThat(result.contexts()).isEqualTo(most);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inputLists")
  void liftEmptyOrMissingContextGivesEmpty(String name, Inputs inputs) {
    List<List<String>> result = read(lift(inputs.blocks(), CONCAT));

    for (int i = 0; i < result.size(); i++) {
      if (inputs.emptyOrMissing(i)) {
        assertThat(result.get(i)).as("context %d", i).isEmpty();
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inputLists")
  void liftCountIsTheLongestInput(String name, Inputs inputs) {
    List<List<String>> result = read(lift(inputs.blocks(), CONCAT));

    for (int i = 0; i < result.size(); i++) {
      if (!inputs.emptyOrMissing(i)) {
        assertThat(result.get(i)).as("context %d", i).hasSize(inputs.longest(i));
      }
    }
  }

  /**
   * Slot j of context i reads each input's value at slot j of its context i, or the last one there
   * when the input's context is shorter; the body gets them in input order.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("inputLists")
  void liftReusesLastValue(String name, Inputs inputs) {
    List<List<String>> result = read(lift(inputs.blocks(), CONCAT));

    for (int i = 0; i < result.size(); i++) {
      if (inputs.emptyOrMissing(i)) {
        continue;
      }
      for (int j = 0; j < result.get(i).size(); j++) {
        List<String> reused = new ArrayList<>();
        for (int k = 0; k < inputs.queues().size(); k++) {
          reused.add(inputs.reused(k, i, j));
        }
        String expected = reused.contains(S) ? S : String.join("+", reused);
        assertThat(result.get(i).get(j)).as("slot %d of context %d", j, i).isEqualTo(expected);
      }
    }
  }

  /**
   * A result slot is SUPPRESS exactly where some input's value there, reused or not, is SUPPRESS,
   * and the body runs once per other slot and never sees SUPPRESS.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("inputLists")
  void liftSuppressSkipsTheBody(String name, Inputs inputs) {
    int[] calls = {0};
    List<String> nullsSeen = new ArrayList<>();

    List<List<String>> result =
        read(
            lift(
                inputs.blocks(),
                values -> {
                  calls[0]++;
                  if (Arrays.asList(values).contains(null)) {
                    nullsSeen.add(Arrays.toString(values));
                  }
                  return "ran";
                }));

    int expectedCalls = 0;
    for (int i = 0; i < result.size(); i++) {
      if (inputs.emptyOrMissing(i)) {
        continue;
      }
      assertThat(result.get(i)).as("context %d", i).hasSize(inputs.longest(i));
      for (int j = 0; j < inputs.longest(i); j++) {
        boolean suppressed = false;
        for (int k = 0; k < inputs.queues().size(); k++) {
          suppressed |= inputs.reused(k, i, j) == null;
        }
        assertThat(result.get(i).get(j))
            .as("slot %d of context %d", j, i)
            .isEqualTo(suppressed ? S : "ran");
        expectedCalls += suppressed ? 0 : 1;
      }
    }
    assertThat(nullsSeen).as("SUPPRESS handed to the body").isEmpty();
    assertThat(calls[0]).as("body calls").isEqualTo(expectedCalls);
  }

  /**
   * obs-lift-reuse lift-3 (#32): the last value read is SUPPRESS, and reusing it gives SUPPRESS.
   */
  @Test
  void liftReusedSuppressGivesSuppress() {
    int[] calls = {0};
    Function<String[], String> concat =
        values -> {
          calls[0]++;
          return values[0] + values[1];
        };

    // o2: in1 is 5, SUPPRESS and in2 is t1, t2, t3: SUPPRESS is read at slot 2 and reused at 3
    QueueBlock o2 =
        lift(
            List.of(
                queue("in1", context("5", S)).block(),
                queue("in2", context("t1", "t2", "t3")).block()),
            concat);
    assertThat(read(o2)).containsExactly(context("5t1", S, S));
    assertThat(calls[0]).as("body calls of o2").isEqualTo(1);

    // o1: in1 is a single SUPPRESS, reused for every tag; the body never runs
    calls[0] = 0;
    QueueBlock o1 =
        lift(
            List.of(
                queue("in1", context(S)).block(), queue("in2", context("t1", "t2", "t3")).block()),
            concat);
    assertThat(read(o1)).containsExactly(context(S, S, S));
    assertThat(calls[0]).as("body calls of o1").isZero();
  }

  /**
   * obs-lift-reuse lift-2 (#32), ADR-0006: SUPPRESS in one input gives SUPPRESS and still consumes
   * the other input's value, so {@code t2} is used up and {@code c7} pairs with {@code t3}.
   */
  @Test
  void liftSuppressConsumesTheOtherInputs() {
    QueueBlock result =
        lift(
            List.of(
                queue("in1", context("a5", S, "c7")).block(),
                queue("in2", context("t1", "t2", "t3")).block()),
            values -> values[0] + values[1]);

    assertThat(read(result)).containsExactly(context("a5t1", S, "c7t3"));
  }

  /**
   * The figure of #2 §1.1 [SAP✓]: concat over {@code 1,2,3 | 4,5,6} and {@code a,b | c,d} gives
   * {@code 1a,2b,3b | 4c,5d,6d}; the reuse stays inside the context.
   */
  @Test
  void liftGivesTheResearchNoteFigure() {
    QueueBlock result =
        lift(
            List.of(
                queue("in1", context("1", "2", "3"), context("4", "5", "6")).block(),
                queue("in2", context("a", "b"), context("c", "d")).block()),
            values -> values[0] + values[1]);

    assertThat(read(result)).containsExactly(context("1a", "2b", "3b"), context("4c", "5d", "6d"));
  }

  /**
   * ADR-0006's fast path and ADR-0004 since #51: a one-input lift reuses its input's context starts
   * and SUPPRESS bitset. A block takes the arrays it is given without copying them, so writing them
   * after the call, against the hand-over rule on purpose, shows in the result.
   */
  @Test
  void oneInputLiftSharesStartsAndBits() {
    String[] values = {null, "a", null, "b"};
    int[] starts = {0, 2, 2, 4};
    long[] suppress = {0b0101L};
    QueueBlock input = QueueBlock.of(values, starts, suppress);

    QueueBlock result = lift(List.of(input), v -> v[0].toUpperCase(Locale.ROOT));
    // witness: before the writes the result is the body over its input, SUPPRESS kept
    assertThat(read(result)).containsExactly(context(S, "A"), context(), context(S, "B"));
    starts[1] = 1;
    starts[2] = 3;
    suppress[0] = 0b0001L;

    assertThat(result.end(0)).as("the end of context 0 after the write").isEqualTo(1);
    assertThat(result.start(2)).as("the start of context 2 after the write").isEqualTo(3);
    assertThat(result.suppressed(2)).as("slot index 2 after its bit was cleared").isFalse();
    assertThat(result.suppressed(0)).as("slot index 0, whose bit stayed").isTrue();
  }

  static Stream<Arguments> interruptible() {
    return Stream.of(
        Arguments.of(
            "one input", inputs("one input", List.of(context("a", S), context("b"), context()))),
        Arguments.of(
            "two inputs",
            inputs(
                "two inputs",
                List.of(context("a", "b"), context("c")),
                List.of(context("x"), context(S, "y")))));
  }

  /** Every loop over contexts checks for interruption (ADR-0021), and a lift loops over them. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptible")
  void interruptedLiftThrowsCancellation(String name, Inputs inputs) {
    List<QueueBlock> blocks = inputs.blocks();
    // witness: the same lift completes on a thread that is not interrupted
    assertThatCode(() -> lift(blocks, CONCAT)).doesNotThrowAnyException();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> lift(blocks, CONCAT)).isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }
  }
}
