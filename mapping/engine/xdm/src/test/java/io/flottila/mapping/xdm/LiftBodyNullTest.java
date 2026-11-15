package io.flottila.mapping.xdm;

import static io.flottila.mapping.xdm.SuppressQueues.S;
import static io.flottila.mapping.xdm.SuppressQueues.context;
import static io.flottila.mapping.xdm.SuppressQueues.queue;
import static io.flottila.mapping.xdm.SuppressQueues.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Only an input's SUPPRESS gives a SUPPRESS slot (ADR-0006), so a body that gives null is a bug:
 * with one input the result would share the input's bitset while a value is null, breaking
 * ADR-0004's invariant. The lift refuses it on either path.
 */
class LiftBodyNullTest {

  static Stream<Arguments> inputs() {
    return Stream.of(
        Arguments.of("one input", List.of(queue("in1", context("a", S, "b")).block())),
        Arguments.of(
            "two inputs",
            List.of(
                queue("in1", context("a", S, "b")).block(),
                queue("in2", context("x", "y", "z")).block())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inputs")
  void aBodyThatGivesNullIsRefused(String name, List<QueueBlock> inputs) {
    Function<String[], String> nullAtB = values -> values[0].equals("b") ? null : values[0];
    // witness: the same lift with a body that gives a value keeps the SUPPRESS of in1
    assertThat(read(Lifting.lift(inputs, values -> values[0])))
        .containsExactly(context("a", S, "b"));

    assertThatThrownBy(() -> Lifting.lift(inputs, nullAtB))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Lifting.lift");
  }
}
