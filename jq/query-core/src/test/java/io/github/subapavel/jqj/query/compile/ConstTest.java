package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The IR's {@code Const} leaf (ADR-0005, ADR-0031, #61 design): it emits its value, opaque to this module, whatever the
 * input; it charges no tick, since it is no step site (ADR-0008); and the verifier takes it as a leaf under the tree
 * rule. Values are plain objects, checked by reference.
 */
class ConstTest {
  private static final Source AT = new Source(0, 1);

  /** The results of {@code executable} on {@code in} under {@code state}, the sink never stopping. */
  private static Executable compile(Node root) {
    return Compiler.compile(root, CompileMode.OPTIMISED);
  }

  private static List<Object> results(Executable executable, RunState state, Object in) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        executable.run(
            state,
            in,
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @Test
  void constEmitsItsValueWhateverTheInput() {
    Object value = new Object();
    Executable constant = compile(new Const(AT, value));
    for (Object in : List.of(new Object(), value, "another input")) {
      assertThat(results(constant, new RunState(), in)).as("results of the constant on %s", in).singleElement().isSameAs(value);
    }

    // ADR-0008: the sink's stop comes back as the run's answer.
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        constant.run(
            new RunState(),
            new Object(),
            v -> {
              emitted.add(v);
              return false;
            });
    assertThat(ranOut).as("run's answer after the sink stopped it").isFalse();
    assertThat(emitted).as("results handed over before the stop").singleElement().isSameAs(value);
  }

  @Test
  void constChargesNoTick() {
    // A run with a step budget of 0: its first tick would end it with a Termination over the steps (ADR-0030).
    RunState state = new RunState();
    state.begin(0, Long.MAX_VALUE, Long.MAX_VALUE);
    Object value = new Object();
    assertThat(results(compile(new Pipe(AT, new Const(AT, new Object()), new Const(AT, value))), state, new Object()))
        .as("results of two constants piped under a step budget of 0")
        .singleElement()
        .isSameAs(value);
    assertThat(state.steps()).as("ticks the two constants charged").isZero();
    state.end();
  }

  @Test
  void verifierTakesConstAsALeaf() {
    // Two distinct constants, and a constant beside the identity, form a tree: it compiles and runs.
    Object in = new Object();
    Object second = new Object();
    assertThat(results(compile(new Pipe(AT, new Const(AT, new Object()), new Const(AT, second))), new RunState(), in))
        .as("results of c1 | c2")
        .singleElement()
        .isSameAs(second);
    assertThat(results(compile(new Pipe(AT, new Const(AT, second), new Identity(AT))), new RunState(), in))
        .as("results of c | .")
        .singleElement()
        .isSameAs(second);

    // ADR-0005's tree rule holds for a Const as for any node: one object used twice is rejected.
    Const shared = new Const(AT, new Object());
    assertThatIllegalStateException()
        .as("compile of Pipe(c, c)")
        .isThrownBy(() -> compile(new Pipe(AT, shared, shared)));
  }
}
