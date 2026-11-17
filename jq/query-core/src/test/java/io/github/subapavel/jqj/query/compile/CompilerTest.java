package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The compiled identity (#33 design, section 1): {@code compile(Node)} gives an {@code Executable} whose push
 * form emits its input (ADR-0008). The inputs are plain objects, because this module never looks inside a value
 * (founding architecture section 14); identity is checked by reference.
 */
class CompilerTest {

  /** The IR of the filter text {@code .}: one {@code Identity} over the characters [0, 1). */
  private static Executable identity() {
    return compile(new Identity(new Source(0, 1)));
  }

  @Test
  void identityEmitsItsInputOnce() {
    Executable executable = identity();
    Object first = new Object();
    Object second = new Object();
    List<Object> emitted = new ArrayList<>();

    boolean ranOut =
        executable.run(
            new RunState(),
            first,
            v -> {
              emitted.add(v);
              return true;
            });

    assertThat(emitted).as("results of . for the first input").singleElement().isSameAs(first);
    // ADR-0008: run answers true when its results ran out, the sink never having stopped it.
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();

    // The same Executable runs again on another input and keeps nothing of the first run.
    emitted.clear();
    boolean ranOutAgain =
        executable.run(
            new RunState(),
            second,
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(emitted).as("results of . for the second input").singleElement().isSameAs(second);
    assertThat(ranOutAgain).as("run's answer on the second input").isTrue();
  }

  @Test
  void identityReturnsTheSinksStop() {
    // ADR-0008: "If emit returns false, that is a stop: the producer emits nothing more and returns false at once."
    Object input = new Object();
    List<Object> emitted = new ArrayList<>();

    boolean ranOut =
        identity()
            .run(
                new RunState(),
                input,
                v -> {
                  emitted.add(v);
                  return false;
                });

    assertThat(ranOut).as("run's answer after the sink stopped it").isFalse();
    assertThat(emitted).as("results handed over before the stop").singleElement().isSameAs(input);
  }

  // #34 design, sections 1 to 3: Pipe(Source, left, right), Intrinsic(Source, Operation) and Operation(name, Fn0);
  // PipeOp.run(state, in, out) = left.run(state, in, v -> right.run(state, v, out)); IntrinsicOp.run =
  // out.emit(fn.apply(state, in)), which ignores the run state for these functions (ADR-0030).

  private static Executable compile(Node root) {
    return Compiler.compile(root, CompileMode.OPTIMISED);
  }

  private static final Source AT = new Source(0, 1);

  /** An Intrinsic whose function maps {@code from} to {@code to}, and fails the test on any other input. */
  private static Intrinsic step(String name, Object from, Object to) {
    return new Intrinsic(
        AT,
        new Operation(
            name,
            (Fn0)
                (state, in) -> {
                  assertThat(in).as("the input %s receives", name).isSameAs(from);
                  return to;
                }),
        List.of());
  }

  private static List<Object> results(Executable executable, Object in) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        executable.run(
            new RunState(),
            in,
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @Test
  void intrinsicEmitsItsFunctionsResult() {
    Object in = new Object();
    Object out = new Object();
    Executable f = compile(step("f", in, out));

    assertThat(results(f, in)).as("results of f, which maps in to out").singleElement().isSameAs(out);

    // ADR-0008: the Intrinsic answers with the sink's answer, so a stop comes back as false.
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        f.run(
            new RunState(),
            in,
            v -> {
              emitted.add(v);
              return false;
            });
    assertThat(ranOut).as("run's answer after the sink stopped it").isFalse();
    assertThat(emitted).as("results handed over before the stop").singleElement().isSameAs(out);
  }

  @Test
  void pipeFeedsTheLeftResultToTheRight() {
    Object in = new Object();
    Object middle = new Object();
    Object out = new Object();

    // f then g: g receives f's result (step asserts it), and the pipe emits g's.
    Executable fThenG = compile(new Pipe(AT, step("f", in, middle), step("g", middle, out)));
    assertThat(results(fThenG, in)).as("results of f | g").singleElement().isSameAs(out);

    // The identity on either side passes its input through.
    assertThat(results(compile(new Pipe(AT, new Identity(AT), step("f", in, out))), in))
        .as("results of . | f")
        .singleElement()
        .isSameAs(out);
    assertThat(results(compile(new Pipe(AT, step("f", in, out), new Identity(AT))), in))
        .as("results of f | .")
        .singleElement()
        .isSameAs(out);

    // Nested to the left and to the right, as the parser nests .a.b.c and .a | .b | .c.
    Object last = new Object();
    Pipe left = new Pipe(AT, new Pipe(AT, step("f", in, middle), step("g", middle, out)), step("h", out, last));
    Pipe right = new Pipe(AT, step("f", in, middle), new Pipe(AT, step("g", middle, out), step("h", out, last)));
    assertThat(results(compile(left), in)).as("results of (f | g) | h").singleElement().isSameAs(last);
    assertThat(results(compile(right), in)).as("results of f | (g | h)").singleElement().isSameAs(last);
  }

  @Test
  void pipeReturnsTheSinksStop() {
    // ADR-0008: "A stop anywhere returns false at once": the right side's false comes back through the pipe.
    Object in = new Object();
    Object out = new Object();
    for (Pipe pipe : List.of(new Pipe(AT, new Identity(AT), step("f", in, out)), new Pipe(AT, new Identity(AT), new Pipe(AT, new Identity(AT), step("f", in, out))))) {
      List<Object> emitted = new ArrayList<>();
      boolean ranOut =
          compile(pipe)
              .run(
                  new RunState(),
                  in,
                  v -> {
                    emitted.add(v);
                    return false;
                  });
      assertThat(ranOut).as("run's answer after the sink stopped %s", pipe).isFalse();
      assertThat(emitted).as("results handed over before the stop").singleElement().isSameAs(out);
    }
  }

  @Test
  void filterExceptionPassesThroughPipeUnchanged() {
    // ADR-0008: a FilterException is every error a filter raises; nothing in a pipe catches it, and it has no stack trace.
    Object in = new Object();
    Object errorValue = new Object();
    FilterException thrown = new FilterException(errorValue);
    Intrinsic fails =
        new Intrinsic(
            AT,
            new Operation(
                "fails",
                (Fn0)
                    (state, v) -> {
                      throw thrown;
                    }),
            List.of());
    assertThat(thrown.value()).as("the error value").isSameAs(errorValue);
    assertThat(thrown.getStackTrace()).as("the stack trace of a FilterException").isEmpty();

    Intrinsic never =
        new Intrinsic(
            AT,
            new Operation(
                "never",
                (Fn0)
                    (state, v) -> {
                      throw new AssertionError("the right side ran after the left side failed");
                    }),
            List.of());
    Object out = new Object();
    for (Pipe pipe : List.of(new Pipe(AT, fails, never), new Pipe(AT, step("f", in, out), new Pipe(AT, new Identity(AT), fails)))) {
      List<Object> emitted = new ArrayList<>();
      Executable executable = compile(pipe);
      assertThatExceptionOfType(FilterException.class)
          .as("run of %s", pipe)
          .isThrownBy(
              () ->
                  executable.run(
                      new RunState(),
                      in,
                      v -> {
                        emitted.add(v);
                        return true;
                      }))
          .isSameAs(thrown);
      assertThat(emitted).as("results of %s", pipe).isEmpty();
    }
  }

  @Test
  void verifierRejectsANodeUsedTwice() {
    // ADR-0005: "The IR must be a tree with no node object used twice."
    Identity dot = new Identity(AT);
    assertThatIllegalStateException().as("compile of Pipe(dot, dot)").isThrownBy(() -> compile(new Pipe(AT, dot, dot)));

    Intrinsic f = step("f", new Object(), new Object());
    assertThatIllegalStateException()
        .as("compile of Pipe(f, Pipe(., f))")
        .isThrownBy(() -> compile(new Pipe(AT, f, new Pipe(AT, new Identity(AT), f))));

    Pipe inner = new Pipe(AT, new Identity(AT), new Identity(AT));
    assertThatIllegalStateException()
        .as("compile of Pipe(inner, Pipe(., inner))")
        .isThrownBy(() -> compile(new Pipe(AT, inner, new Pipe(AT, new Identity(AT), inner))));
  }

  @Test
  void verifierAcceptsEqualDistinctNodes() {
    // Two Identity records over the same Source are equal but distinct objects: a tree, which compiles and runs.
    Object in = new Object();
    Identity a = new Identity(AT);
    Identity b = new Identity(AT);
    assertThat(a).as("witness: the two nodes are equal records").isEqualTo(b).isNotSameAs(b);
    assertThat(results(compile(new Pipe(AT, a, new Pipe(AT, b, new Identity(AT)))), in))
        .as("results of . | . | .")
        .singleElement()
        .isSameAs(in);

    // The same for two equal Intrinsics sharing one Operation.
    Operation op = new Operation("same", (Fn0) (state, v) -> v);
    assertThat(results(compile(new Pipe(AT, new Intrinsic(AT, op, List.of()), new Intrinsic(AT, op, List.of()))), in))
        .as("results of same | same")
        .singleElement()
        .isSameAs(in);
  }
}
