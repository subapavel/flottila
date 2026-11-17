package dev.rail8.jq.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.rail8.jq.query.ir.Collect;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Termination;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A Collect charges its container once and each element before it stores it (ADR-0035, the Collector contract): 64 B
 * per container, the measured fixed charge (bench/RESULTS.md, 2026-11-13), and 8 B per element (ADR-0017). A charge
 * that would pass the bytes-built budget ends the run with BYTES_BUILT before the collector sees anything.
 */
class CollectChargesTest {
  private static final Source AT = new Source(0, 1);

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  /** {@code [body]}: a list of the body's results. */
  private static Collect array(Node body) {
    return new Collect(AT, body, (elements, length) -> List.of(Arrays.copyOf(elements, length)));
  }

  private static Intrinsic none() {
    return new Intrinsic(AT, new Operation("none", (Fn0) (state, in) -> CallForm.NONE), List.of());
  }

  /** A run state in a run whose bytes-built budget is {@code bytes}. */
  private static RunState building(long bytes) {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, bytes, Long.MAX_VALUE);
    return state;
  }

  private static List<Object> results(Node root, CompileMode mode, RunState state) {
    List<Object> emitted = new ArrayList<>();
    assertThat(
            Compiler.compile(root, mode)
                .run(
                    state,
                    new Object(),
                    v -> {
                      emitted.add(v);
                      return true;
                    }))
        .as("run's answer when the sink never stops")
        .isTrue();
    return emitted;
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void anEmptyCollectChargesOneContainer(CompileMode mode) {
    RunState state = building(Long.MAX_VALUE);
    assertThat(results(array(none()), mode, state)).as("results of [none]").containsExactly(List.of());
    assertThat(state.built()).as("bytes built by [none]: one container").isEqualTo(64);

    // A budget one byte short of the container ends the run before the collector runs.
    Termination t = assertThatExceptionOfType(Termination.class).as("[none] with 63 bytes built").isThrownBy(() -> results(array(none()), mode, building(63))).actual();
    assertThat(t.budget()).as("budget of [none] with 63 bytes built").isEqualTo(Termination.Budget.BYTES_BUILT);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void eachElementChargesEightBytes(CompileMode mode) {
    Node three = array(new Comma(AT, c(1), new Comma(AT, c(2), c(3))));
    RunState state = building(Long.MAX_VALUE);
    assertThat(results(three, mode, state)).as("results of [1, 2, 3]").containsExactly(List.of(1, 2, 3));
    assertThat(state.built()).as("bytes built by [1, 2, 3]: a container and three elements").isEqualTo(88);

    // 64 + 2 x 8 bytes hold the container and two elements; the third element's charge ends the run.
    Termination t = assertThatExceptionOfType(Termination.class).as("[1, 2, 3] with 80 bytes built").isThrownBy(() -> results(three, mode, building(80))).actual();
    assertThat(t.budget()).as("budget of [1, 2, 3] with 80 bytes built").isEqualTo(Termination.Budget.BYTES_BUILT);
  }
}
