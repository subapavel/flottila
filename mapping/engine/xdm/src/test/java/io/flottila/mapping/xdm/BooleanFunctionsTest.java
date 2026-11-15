package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.SuppressQueues.Queue;
import java.util.ArrayList;
import java.util.Collections;
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
 * The queue algebra of the function family boolean (#78, ADR-0025 "Families"), as observed on PO
 * 7.5 and CPI (obs-boolean, F-44): {@code ifWithoutElse} casts its condition as PO's {@code
 * Bool.toBoolean} does and passes its {@code then} value where the condition is true; a false
 * condition gives SUPPRESS at its slot; the result has as many contexts as {@code then}, a context
 * beyond the condition's last is empty, and an empty context against a non-empty one gives SUPPRESS
 * at each of the other's slots. The property tests (ADR-0018) are seeded loops, without jqwik: the
 * edges and 200 seeded pairs against a reference written here, and the laws that tie the result to
 * the Lift and to {@code then}.
 */
class BooleanFunctionsTest {

  /** Conditions that cast: true and false in several cases, and 1 and 0. */
  private static final String[] CONDITIONS = {"true", "false", "TRUE", "True", "FALSE", "1", "0"};

  private static final String[] VALUES = {"", "a", " b ", "true", "Grüße", "😀"};

  /** A seeded queue of {@code sample}: empty contexts, queues without contexts, SUPPRESS. */
  private static Queue seeded(String name, RandomGenerator random, String[] sample) {
    List<List<String>> contexts = new ArrayList<>();
    int count = random.nextInt(8) == 0 ? random.nextInt(30) : random.nextInt(5);
    for (int c = 0; c < count; c++) {
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(8) == 0 ? random.nextInt(70) : random.nextInt(4);
      for (int k = 0; k < slots; k++) {
        context.add(random.nextInt(6) == 0 ? S : sample[random.nextInt(sample.length)]);
      }
      contexts.add(Collections.unmodifiableList(context));
    }
    return new Queue(name, List.copyOf(contexts));
  }

  /** The edges, then 200 seeded pairs. */
  static Stream<Arguments> pairs() {
    Stream<Queue[]> edges =
        Stream.of(
            new Queue[] {queue("no context"), queue("no context")},
            new Queue[] {queue("no context"), queue("one", context("a"))},
            new Queue[] {queue("one", context("true")), queue("no context")},
            new Queue[] {queue("one empty context", context()), queue("one", context("a"))},
            new Queue[] {queue("one", context("false")), queue("one empty context", context())},
            new Queue[] {
              queue("all false", context("false", "FALSE", "0")),
              queue("a b c", context("a", "b", "c"))
            },
            new Queue[] {
              queue("true false true", context("true", "false", "true")),
              queue("x y", context("x", "y"))
            },
            new Queue[] {
              queue("one true", context("true")), queue("p q r", context("p", "q", "r"))
            },
            new Queue[] {
              queue("SUPPRESS true false", context(S, "true", "false")),
              queue("a SUPPRESS b", context("a", S, "b"))
            },
            new Queue[] {
              queue("two contexts", context("true"), context("true", "false")),
              queue("three contexts", context("a"), context("b", "c"), context("d"))
            });
    Stream<Queue[]> seeded =
        LongStream.rangeClosed(1, 200)
            .mapToObj(
                seed -> {
                  RandomGenerator random = new SplittableRandom(seed);
                  return new Queue[] {
                    seeded("condition " + seed, random, CONDITIONS),
                    seeded("then " + seed, random, VALUES)
                  };
                });
    return Stream.concat(edges, seeded).map(p -> Arguments.of(p[0] + " / " + p[1], p[0], p[1]));
  }

  /** The truth value of a condition of {@link #CONDITIONS}, as the class comment says. */
  private static boolean isTrue(String condition) {
    return condition.equals("1") || condition.equalsIgnoreCase("true");
  }

  /** The reference: what the class comment says, written slot by slot over the lists. */
  private static List<List<String>> reference(Queue condition, Queue then) {
    List<List<String>> result = new ArrayList<>();
    for (int c = 0; c < then.contexts().size(); c++) {
      List<String> values = then.contexts().get(c);
      List<String> slots = new ArrayList<>();
      if (c < condition.contexts().size()) {
        List<String> conditions = condition.contexts().get(c);
        for (int j = 0; j < Math.max(conditions.size(), values.size()); j++) {
          String test =
              conditions.isEmpty() ? S : conditions.get(Math.min(j, conditions.size() - 1));
          String value = values.isEmpty() ? S : values.get(Math.min(j, values.size() - 1));
          slots.add(test == S || value == S || !isTrue(test) ? S : value);
        }
      }
      result.add(slots);
    }
    return result;
  }

  // --- property tests ---

  @ParameterizedTest(name = "{0}")
  @MethodSource("pairs")
  void ifWithoutElseAgreesWithTheReference(String name, Queue condition, Queue then) {
    QueueBlock result = BooleanFunctions.ifWithoutElse(condition.block(), then.block());

    assertThat(read(result)).isEqualTo(reference(condition, then));
  }

  /**
   * Where context i of both inputs holds slots, context i of the result has the Lift's slots and
   * the Lift's values where the condition is true: every other slot is SUPPRESS.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("pairs")
  void whereBothContextsHoldSlotsItHasTheLiftsSlots(String name, Queue condition, Queue then) {
    List<List<String>> result =
        read(BooleanFunctions.ifWithoutElse(condition.block(), then.block()));
    List<List<String>> lift =
        read(Lifting.lift(List.of(condition.block(), then.block()), v -> v[0] + "\u0000" + v[1]));

    int both = Math.min(condition.contexts().size(), then.contexts().size());
    for (int c = 0; c < both; c++) {
      if (condition.contexts().get(c).isEmpty() || then.contexts().get(c).isEmpty()) {
        continue;
      }
      assertThat(result.get(c)).as("context %d", c).hasSameSizeAs(lift.get(c));
      for (int j = 0; j < lift.get(c).size(); j++) {
        String pair = lift.get(c).get(j);
        String test = pair == S ? null : pair.substring(0, pair.indexOf('\u0000'));
        String expected = test == null || !isTrue(test) ? S : pair.substring(test.length() + 1);
        assertThat(result.get(c).get(j)).as("context %d slot %d", c, j).isEqualTo(expected);
      }
    }
  }

  /**
   * The result has as many contexts as {@code then}; a context beyond the condition's last is
   * empty, and every other holds the slots of the longer of the two contexts.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("pairs")
  void itHasTheContextsOfThen(String name, Queue condition, Queue then) {
    List<List<String>> result =
        read(BooleanFunctions.ifWithoutElse(condition.block(), then.block()));

    assertThat(result).hasSameSizeAs(then.contexts());
    for (int c = 0; c < result.size(); c++) {
      int slots =
          c < condition.contexts().size()
              ? Math.max(condition.contexts().get(c).size(), then.contexts().get(c).size())
              : 0;
      assertThat(result.get(c)).as("context %d", c).hasSize(slots);
    }
  }

  /** A condition that is true at every slot of {@code then} gives {@code then}. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("pairs")
  void anAllTrueConditionGivesThen(String name, Queue condition, Queue then) {
    List<List<String>> allTrue = new ArrayList<>();
    for (List<String> context : then.contexts()) {
      allTrue.add(Collections.nCopies(context.size(), "true"));
    }
    Queue always = new Queue("all true", allTrue);

    assertThat(read(BooleanFunctions.ifWithoutElse(always.block(), then.block())))
        .isEqualTo(then.contexts());
  }

  /** Witness for the seeded pairs: some reach each shape the reference tells apart. */
  @Test
  void someSeededPairsHaveEveryShape() {
    boolean emptyAgainstValues = false;
    boolean beyondTheCondition = false;
    boolean beyondThen = false;
    for (Arguments arguments : pairs().skip(10).toList()) {
      Queue condition = (Queue) arguments.get()[1];
      Queue then = (Queue) arguments.get()[2];
      beyondTheCondition |= then.contexts().size() > condition.contexts().size();
      beyondThen |= condition.contexts().size() > then.contexts().size();
      for (int c = 0; c < Math.min(condition.contexts().size(), then.contexts().size()); c++) {
        emptyAgainstValues |=
            condition.contexts().get(c).isEmpty() != then.contexts().get(c).isEmpty();
      }
    }
    assertThat(emptyAgainstValues).as("an empty context against values").isTrue();
    assertThat(beyondTheCondition).as("a then context beyond the condition's last").isTrue();
    assertThat(beyondThen).as("a condition context beyond then's last").isTrue();
  }

  // --- the edges by name ---

  /** Observed (fn/ifWithoutElse/001, 005, 007): false gives SUPPRESS, and TRUE is true (003). */
  @Test
  void aFalseConditionGivesSuppressAtItsSlot() {
    Queue condition = queue("c", context("true", "false", "true"), context("false", "TRUE"));
    Queue then = queue("t", context("a", "b", "c"), context("d", "e"));

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context("a", S, "c"), context(S, "e"));
  }

  /** Observed (fn/ifWithoutElse/007): a SUPPRESS condition gives SUPPRESS, as a SUPPRESS value. */
  @Test
  void suppressInEitherInputIsSuppress() {
    Queue condition = queue("c", context(S, "true", "false"));
    Queue then = queue("t", context("a", S, "b"));

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context(S, S, S));
  }

  /**
   * Observed (fn/ifWithoutElse/008, display queue of X1): an empty condition context against the
   * value z, and a false condition against an empty value context, each give one SUPPRESS.
   */
  @Test
  void anEmptyContextAgainstValuesGivesSuppressAtEachSlot() {
    Queue condition = queue("c", context(), context("false"), context("true", "false"));
    Queue then = queue("t", context("z"), context(), context("x", "y"));

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context(S), context(S), context("x", S));
  }

  /** Observed (fn/ifWithoutElse/009): a value context beyond the condition's last is empty. */
  @Test
  void aThenContextBeyondTheConditionsLastIsEmpty() {
    Queue condition = queue("c", context("true", "true"));
    Queue then = queue("t", context("a"), context("b"));

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context("a", "a"), context());
  }

  /** Observed (fn/ifWithoutElse/010): a condition context beyond then's last gives no context. */
  @Test
  void aConditionContextBeyondThensLastGivesNone() {
    Queue condition = queue("c", context("true"), context("true"));
    Queue then = queue("t", context("x"));

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context("x"));
  }

  /** The condition casts as {@link BooleanFunctions#truth} does: 1 and true in any case. */
  @Test
  void theConditionCastsAsPoDoes() {
    Queue condition = queue("c", context("TRUE", "tRuE", "1", "FALSE", "0"));
    Queue then = queue("t", context("a", "b", "c", "d", "e"));

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context("a", "b", "c", S, S));
  }

  /** A condition that is no boolean raises the data error, at the first such slot in order. */
  @Test
  void aConditionThatIsNoBooleanIsADataError() {
    QueueBlock condition = queue("c", context("true"), context("", "keep")).block();
    QueueBlock then = queue("t", context("a"), context("b", "c")).block();

    assertThatThrownBy(() -> BooleanFunctions.ifWithoutElse(condition, then))
        .isInstanceOf(QueueDataException.class)
        .hasMessage("Cannot cast '' to boolean");
  }

  /**
   * Only a condition paired with a value is cast: one against SUPPRESS, against an empty value
   * context or beyond then's last context gives no error.
   */
  @Test
  void aConditionWithoutAValueIsNotCast() {
    Queue condition = queue("c", context("keep", "true"), context("keep"), context("keep"));
    Queue then = queue("t", context(S, "a"), context());

    assertThat(read(BooleanFunctions.ifWithoutElse(condition.block(), then.block())))
        .containsExactly(context(S, "a"), context(S));
  }

  /**
   * PO's {@code Bool.toBoolean} as F-37 and F-44 observed it: {@code 1} and {@code true} in any
   * case are true, {@code 0} and {@code false} in any case false, and any other value raises.
   */
  @Test
  void truthCastsAsBoolToBoolean() {
    for (String value : List.of("1", "true", "TRUE", "True", "tRuE")) {
      assertThat(BooleanFunctions.truth(value)).as(value).isTrue();
    }
    for (String value : List.of("0", "false", "FALSE", "False", "fAlSe")) {
      assertThat(BooleanFunctions.truth(value)).as(value).isFalse();
    }
    // white space is part of a value; the case is folded for ASCII letters only, so U+017F, the
    // long s, which equalsIgnoreCase takes for s, gives no false
    for (String value : List.of("", " true", "keep", "yes", "01", "1.0", "falſe")) {
      assertThatThrownBy(() -> BooleanFunctions.truth(value))
          .as(value)
          .isInstanceOf(QueueDataException.class)
          .hasMessage("Cannot cast '" + value + "' to boolean");
    }
  }

  @Test
  void anInterruptedThreadStopsIt() {
    QueueBlock one = queue("one", context("true")).block();
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> BooleanFunctions.ifWithoutElse(one, one))
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining("ifWithoutElse");
    } finally {
      Thread.interrupted();
    }
  }
}
