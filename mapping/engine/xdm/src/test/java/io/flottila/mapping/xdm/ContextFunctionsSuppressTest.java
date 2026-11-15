package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static io.flottila.mapping.xdm.SuppressQueues.suppressedSlots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The context functions over queues that hold SUPPRESS (#51, ADR-0004, ADR-0006): {@code createIf}
 * gives the empty string where its condition is true and SUPPRESS where it is false or SUPPRESS,
 * refuses any other value (#133, F-37), and keeps the contexts, sharing its input's context starts;
 * {@code removeContexts} keeps each SUPPRESS at its slot. Property tests (ADR-0018) run over the
 * edges and 200 seeded queues of {@link SuppressQueues}.
 */
class ContextFunctionsSuppressTest {

  static Stream<Arguments> queues() {
    return SuppressQueues.queues().map(q -> Arguments.of(q.name(), q));
  }

  /**
   * The cast (ADR-0006 as amended with #133, F-37): {@code 1} or {@code true} in any case is true
   * and gives the empty string (#2 §4); {@code 0} or {@code false} in any case is false and gives
   * SUPPRESS, as a SUPPRESS slot does.
   */
  @Test
  void createIfCastsAsPoDoes() {
    Queue conditions =
        queue(
            "conditions",
            context("1", "true", "TRUE", "True", "tRuE"),
            context("0", "false", "FALSE", "False", "fAlSe", S));

    QueueBlock result = ContextFunctions.createIf(conditions.block());

    assertThat(read(result))
        .containsExactly(context("", "", "", "", ""), context(S, S, S, S, S, S));
  }

  /**
   * Any other value is no boolean and raises the data error with PO's text (F-37): white space is
   * part of a value, and the case is folded for ASCII letters only, so U+017F, the long s, which
   * {@code equalsIgnoreCase} takes for {@code s}, gives no {@code false}.
   */
  @ParameterizedTest(name = "\"{0}\"")
  @MethodSource("noBooleans")
  void createIfRefusesAValueThatIsNoBoolean(String value) {
    QueueBlock conditions = queue("conditions", context("1", S, value)).block();

    assertThatThrownBy(() -> ContextFunctions.createIf(conditions))
        .isInstanceOf(QueueDataException.class)
        .hasMessage("Cannot cast '" + value + "' to boolean");
  }

  static Stream<String> noBooleans() {
    return Stream.of(
        "", "no", "skip", "yes", " true", "true ", "1.0", "01", "t", "2", "-1", "falſe");
  }

  /** The error names the first value, in slot order across the contexts, that is no boolean. */
  @Test
  void createIfNamesTheFirstValueThatIsNoBoolean() {
    QueueBlock conditions = queue("conditions", context("1", S), context("no", "skip")).block();

    assertThatThrownBy(() -> ContextFunctions.createIf(conditions))
        .isInstanceOf(QueueDataException.class)
        .hasMessage("Cannot cast 'no' to boolean");
  }

  /** The boolean createIf reads in {@code condition}, a value, or null where it raises. */
  private static Boolean cast(String condition) {
    String folded = condition.toLowerCase(Locale.ROOT);
    if (condition.equals("1") || folded.equals("true")) {
      return true;
    }
    if (condition.equals("0") || folded.equals("false")) {
      return false;
    }
    return null;
  }

  /** Whether createIf casts every value of {@code queue} without the data error. */
  private static boolean casts(Queue queue) {
    return queue.flat().stream().allMatch(v -> v == null || cast(v) != null);
  }

  /**
   * Property test: createIf keeps the contexts of its input, slot for slot, and each slot is the
   * empty string where the input's is true, else SUPPRESS; where a value is no boolean, it raises
   * the data error naming the first such value.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void createIfKeepsContexts(String name, Queue queue) {
    QueueBlock input = queue.block();

    if (casts(queue)) {
      QueueBlock result = ContextFunctions.createIf(input);
      assertThat(result.contexts()).isEqualTo(queue.contexts().size());
      List<List<String>> expected = new ArrayList<>();
      for (List<String> context : queue.contexts()) {
        List<String> slots = new ArrayList<>();
        for (String condition : context) {
          slots.add(condition != null && cast(condition) ? "" : S);
        }
        expected.add(slots);
      }
      assertThat(read(result)).isEqualTo(expected);
    } else {
      String first =
          queue.flat().stream().filter(v -> v != null && cast(v) == null).findFirst().orElseThrow();
      assertThatThrownBy(() -> ContextFunctions.createIf(input))
          .isInstanceOf(QueueDataException.class)
          .hasMessage("Cannot cast '" + first + "' to boolean");
    }
    // a block is immutable: the call leaves its input as it was
    assertThat(read(input)).isEqualTo(queue.contexts());
  }

  /** Witness for the property: some queues cast, a true and a false among them, and some raise. */
  @Test
  void someQueuesCastAndSomeRaise() {
    List<Queue> all = SuppressQueues.queues().toList();
    List<Queue> casting = all.stream().filter(ContextFunctionsSuppressTest::casts).toList();

    assertThat(casting).hasSizeBetween(1, all.size() - 1);
    assertThat(casting.stream().flatMap(q -> q.flat().stream()).filter(v -> v != null))
        .anyMatch(v -> cast(v))
        .anyMatch(v -> !cast(v));
  }

  /**
   * ADR-0004 since #51: a per-slot function shares the context starts. A block takes the arrays it
   * is given without copying them, so writing the starts after the call shows the sharing.
   */
  @Test
  void createIfSharesTheStarts() {
    int[] starts = {0, 2, 3};
    QueueBlock input =
        QueueBlock.of(
            new String[] {"true", null, "0"},
            starts,
            SuppressQueues.bits(new String[] {"true", null, "0"}));

    QueueBlock result = ContextFunctions.createIf(input);
    // witness: before the write the result splits as its input does
    assertThat(read(result)).containsExactly(context("", S), context(S));
    starts[1] = 1;

    assertThat(result.end(0)).as("the end of context 0 after the write").isEqualTo(1);
    assertThat(result.start(1)).as("the start of context 1 after the write").isEqualTo(1);
  }

  /**
   * Property test: removeContexts shares the values and the bitset (ADR-0004), so each SUPPRESS
   * stays SUPPRESS at its own slot, and each value at its own.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void removeContextsKeepsSuppressAtItsSlot(String name, Queue queue) {
    QueueBlock input = queue.block();

    QueueBlock result = ContextFunctions.removeContexts(input);

    assertThat(read(result)).containsExactly(queue.flat());
    assertThat(suppressedSlots(result)).isEqualTo(suppressedSlots(input));
  }

  /**
   * ADR-0004 since #51: {@code withContextStarts}, on which removeContexts stands, shares the
   * values and the bitset. Clearing the second word of the bitset after the call breaks the
   * hand-over rule on purpose, and the result reads the cleared bit.
   */
  @Test
  void removeContextsSharesTheBitset() {
    String[] values = SuppressQueues.slots(70, 2, 66).toArray(String[]::new);
    long[] suppress = SuppressQueues.bits(values);
    QueueBlock input = QueueBlock.of(values, new int[] {0, 30, 70}, suppress);

    QueueBlock result = ContextFunctions.removeContexts(input);
    // witness: before the write the result reads SUPPRESS at slot indexes 2 and 66
    assertThat(result.suppressed(2)).isTrue();
    assertThat(result.suppressed(66)).isTrue();
    suppress[1] = 0;

    assertThat(result.suppressed(66)).as("slot index 66 after its bit was cleared").isFalse();
  }
}
