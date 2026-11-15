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
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code exists} and {@code mapWithDefault} (#57) at {@code ContextFunctions}, as PO 7.50 and CPI
 * run them (F-48). Both tell an empty context from one with slots: a SUPPRESS slot counts like a
 * value, so a context of SUPPRESS only is not empty, and {@code ""} is a value. exists gives one
 * {@code true} per slot and one {@code false} per empty context; mapWithDefault fills only an empty
 * context. Worked examples first, then property tests (ADR-0018) over the edges and 200 seeded
 * queues of {@link SuppressQueues}, against list references written here.
 */
class ContextFunctionsExistsMapWithDefaultTest {

  static Stream<Arguments> queues() {
    return SuppressQueues.queues().map(q -> Arguments.of(q.name(), q));
  }

  // --- exists

  /**
   * One true per slot, SUPPRESS and {@code ""} included, and one false per empty context, each
   * context kept (fn/exists/003, 004: ctx-4 shows {@code true,true | false | false | true}).
   */
  @Test
  void existsGivesTruePerSlotAndFalsePerEmptyContext() {
    Queue queue =
        queue("worked", context("a", "b"), context(), context(S), context(S, ""), context(""));

    assertThat(read(ContextFunctions.exists(queue.block())))
        .containsExactly(
            context("true", "true"),
            context("false"),
            context("true"),
            context("true", "true"),
            context("true"));
  }

  /** A queue with no context, as under an absent parent, gives no context (ADR-0004). */
  @Test
  void existsOverNoContextGivesNoContext() {
    assertThat(ContextFunctions.exists(queue("no context").block()).contexts()).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void existsGivesTruePerSlotOrFalseForAnEmptyContext(String name, Queue queue) {
    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      expected.add(
          context.isEmpty() ? context("false") : Collections.nCopies(context.size(), "true"));
    }

    assertThat(read(ContextFunctions.exists(queue.block()))).isEqualTo(expected);
  }

  // --- mapWithDefault

  /** The SAP example (#2 §4): {@code A|B1,B2| |C| |D → A | B1,B2 | Default | C | Default |D}. */
  @Test
  void mapWithDefaultFillsEachEmptyContextWithOneDefault() {
    Queue sap =
        queue(
            "SAP",
            context("A"),
            context("B1", "B2"),
            context(),
            context("C"),
            context(),
            context("D"));

    assertThat(read(ContextFunctions.mapWithDefault(sap.block(), "Default")))
        .containsExactly(
            context("A"),
            context("B1", "B2"),
            context("Default"),
            context("C"),
            context("Default"),
            context("D"));
  }

  /**
   * A context of SUPPRESS only is not empty and is kept, as SUPPRESS next to a value is, and {@code
   * ""} is a value (fn/mapWithDefault/002, obs-context-functions ctx-2); only the empty context
   * gets the default.
   */
  @Test
  void mapWithDefaultKeepsASuppressOnlyContext() {
    Queue queue =
        queue("worked", context(S), context(S, S), context(), context(S, "a", S), context(""));

    assertThat(read(ContextFunctions.mapWithDefault(queue.block(), "D")))
        .containsExactly(context(S), context(S, S), context("D"), context(S, "a", S), context(""));
  }

  /**
   * No context stays no context, and one empty context becomes one default: what tells
   * removeContexts' one empty context apart from zero contexts (#50 review).
   */
  @Test
  void mapWithDefaultKeepsNoContextAndFillsOneEmptyContext() {
    QueueBlock none = queue("no context").block();

    assertThat(ContextFunctions.mapWithDefault(none, "D").contexts()).isZero();
    assertThat(read(ContextFunctions.mapWithDefault(ContextFunctions.removeContexts(none), "D")))
        .containsExactly(context("D"));
  }

  /** The default {@code ""} fills an empty context with the value {@code ""}. */
  @Test
  void mapWithDefaultOfTheEmptyStringGivesTheEmptyStringAsAValue() {
    Queue queue = queue("worked", context(), context("a"));

    assertThat(read(ContextFunctions.mapWithDefault(queue.block(), "")))
        .containsExactly(context(""), context("a"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("queues")
  void mapWithDefaultReplacesEveryEmptyContextAndKeepsTheOthers(String name, Queue queue) {
    List<List<String>> expected = new ArrayList<>();
    for (List<String> context : queue.contexts()) {
      expected.add(context.isEmpty() ? context("dflt") : context);
    }

    assertThat(read(ContextFunctions.mapWithDefault(queue.block(), "dflt"))).isEqualTo(expected);
  }

  // --- interruption (ADR-0021): the loop over contexts checks, before the block's own checks

  static Stream<Arguments> interruptible() {
    QueueBlock queue = queue("one context", context(S, "a")).block();
    return Stream.of(
        Arguments.of("exists", (Supplier<QueueBlock>) () -> ContextFunctions.exists(queue)),
        Arguments.of(
            "mapWithDefault",
            (Supplier<QueueBlock>) () -> ContextFunctions.mapWithDefault(queue, "D")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptible")
  void onAnInterruptedThreadTheFunctionThrowsCancellationExceptionByName(
      String function, Supplier<QueueBlock> call) {
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(call.get()).isNotNull();

    Thread.currentThread().interrupt();
    try {
      // its own check, by its name: the block it builds checks too, with another message
      assertThatThrownBy(call::get)
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining(function);
    } finally {
      Thread.interrupted();
    }
  }
}
