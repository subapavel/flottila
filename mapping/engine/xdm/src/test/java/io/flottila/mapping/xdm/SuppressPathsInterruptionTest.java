package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every loop over slots or contexts checks for interruption (ADR-0021), also on the paths #51 adds.
 * Each call here gives a block on a thread that is not interrupted and throws {@link
 * CancellationException} on one that is. The lift cases also show which of the lift's checks fires:
 * the one before each context, by its message, and the one before each slot, by the body calls
 * made.
 */
class SuppressPathsInterruptionTest {

  static Stream<Arguments> interruptible() {
    QueueBlock conditions = queue("conditions", context("1", S, "0")).block();
    QueueBlock suppressed = queue("suppressed", context(S, "a"), context(S)).block();
    return Stream.of(
        Arguments.of(
            "of over empty contexts only",
            (Supplier<QueueBlock>) () -> QueueBlock.of(new String[0], new int[] {0, 0, 0})),
        Arguments.of(
            "createIf", (Supplier<QueueBlock>) () -> ContextFunctions.createIf(conditions)),
        // it shares the values and the bitset, so its new starts are the one loop it runs
        Arguments.of(
            "removeContexts",
            (Supplier<QueueBlock>) () -> ContextFunctions.removeContexts(suppressed)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interruptible")
  void onAnInterruptedThreadACallThrowsCancellationExceptionAndGivesNoBlock(
      String call, Supplier<QueueBlock> block) {
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(block.get()).isNotNull();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(block::get).isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }
  }

  /**
   * A lift over two inputs whose contexts are all empty or missing runs no slot, so only its check
   * before each context is the lift's own: the block it builds checks too, with another message.
   */
  @Test
  void aLiftOverEmptyOrMissingContextsOnlyChecksBeforeEachContext() {
    List<QueueBlock> inputs =
        List.of(
            queue("in1", context(), context("a"), context()).block(),
            queue("in2", context(), context()).block());
    // witness: on a thread that is not interrupted it gives three empty contexts and no body call
    assertThat(read(Lifting.lift(inputs, values -> values[0])))
        .containsExactly(context(), context(), context());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> Lifting.lift(inputs, values -> values[0]))
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining("a lift was running");
    } finally {
      Thread.interrupted();
    }
  }

  /**
   * A lift over two inputs checks before each slot, not only before each context: a body that
   * interrupts its thread is the last one run, inside the one context.
   */
  @Test
  void aLiftInterruptedWithinAContextStopsBeforeItsNextSlot() {
    List<QueueBlock> inputs =
        List.of(
            queue("in1", context("a", "b", "c")).block(),
            queue("in2", context("x", "y", "z")).block());
    int[] calls = {0};
    // witness: the same lift runs the body once per slot when nothing interrupts it
    assertThat(read(Lifting.lift(inputs, values -> values[0] + values[1])))
        .containsExactly(context("ax", "by", "cz"));

    try {
      assertThatThrownBy(
              () ->
                  Lifting.lift(
                      inputs,
                      values -> {
                        calls[0]++;
                        Thread.currentThread().interrupt();
                        return values[0];
                      }))
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }
    assertThat(calls[0]).as("body calls").isEqualTo(1);
  }

  /**
   * createIf's own check before each slot fires first, by its message: the block it builds checks
   * too, with a message of its own.
   */
  @Test
  void onAnInterruptedThreadCreateIfStopsBeforeASlotNamingItself() {
    QueueBlock conditions = queue("conditions", context("1", S, "0")).block();
    // witness: the same call gives a block on a thread that is not interrupted
    assertThat(ContextFunctions.createIf(conditions)).isNotNull();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> ContextFunctions.createIf(conditions))
          .isInstanceOf(CancellationException.class)
          .hasMessage("createIf was running when the thread was interrupted");
    } finally {
      Thread.interrupted();
    }
  }
}
