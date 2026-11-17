package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The wrap depth of ADR-0039 (GLOSSARY "Wrap depth", "Downstream error") over plain objects (founding §14): an error
 * thrown from a Try's sink leaves with its depth + 1, that Try's run unwraps it (− 1) and re-raises it, and a Try runs
 * its handler only on an error of depth 0, on that error's value. The caller of {@code Executable.run}, which is what
 * the engine's filter run calls, sees depth 0. An error of the handler propagates; after a caught error the body is
 * abandoned.
 *
 * <p>Shapes the test-author fixed from ADR-0039 (no draft text reached the red): {@code Try(Source, Node body, Node
 * handler)} in {@code query.ir}; {@code FilterException.depth()}, {@code wrap()} and {@code unwrap()} on the instance
 * (AltBind "rethrows the instance it caught"). Compile and run take #67's parameters ({@code Compiler.compile(Node, int,
 * CompileMode)}, {@code Executable.run(RunState, Object[], Object, Sink)}; #67 blocks #68).
 */
class FilterExceptionTest {
  private static final Source AT = new Source(0, 1);

  /** An Fn0 that raises its input as the error value, as the engine's {@code error} builtin does. */
  private static final Fn0 RAISE = (state, in) -> {
    throw new FilterException(in);
  };

  private static Executable compile(Node root, CompileMode mode) {
    return Compiler.compile(root, 0, mode);
  }

  private static Intrinsic raise() {
    return new Intrinsic(AT, new Operation("raise", RAISE), List.of());
  }

  /** {@code value | raise}. */
  private static Node raise(Object value) {
    return new Pipe(AT, new Const(AT, value), raise());
  }

  /** The results of {@code root} on {@code in}; the sink never stops. */
  private static List<Object> results(Node root, CompileMode mode, Object in) {
    List<Object> emitted = new ArrayList<>();
    Sink all =
        v -> {
          emitted.add(v);
          return true;
        };
    assertThat(compile(root, mode).run(new RunState(), new Object[0], in, all)).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @Test
  void aNewErrorHasDepthZero() {
    Object value = new Object();
    FilterException e = new FilterException(value);
    assertThat(e.depth()).as("depth of a new error").isEqualTo(0);
    assertThat(e.value()).as("its value").isSameAs(value);
  }

  @Test
  void wrapAddsOneAndUnwrapTakesOneOff() {
    Object value = new Object();
    FilterException e = new FilterException(value);
    e.wrap();
    assertThat(e.depth()).as("depth after one wrap").isEqualTo(1);
    e.wrap();
    assertThat(e.depth()).as("depth after two wraps").isEqualTo(2);
    e.unwrap();
    assertThat(e.depth()).as("depth after two wraps and one unwrap").isEqualTo(1);
    e.unwrap();
    assertThat(e.depth()).as("depth after two wraps and two unwraps").isEqualTo(0);
    assertThat(e.value()).as("the value is kept").isSameAs(value);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void anErrorOfTheBodyRunsTheHandlerOnItsValue(CompileMode mode) {
    Object payload = new Object();
    assertThat(results(new Try(AT, raise(payload), new Identity(AT)), mode, new Object())).as("results of try raise catch .").containsExactly(payload);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void anErrorOfTheSinkLeavesTheRootTryAtDepthZero(CompileMode mode) {
    // The sink is downstream of the root Try: the Try lets the error through, and its run hands it back unwrapped.
    Object result = new Object();
    Object handler = new Object();
    FilterException thrown = new FilterException(new Object());
    List<Object> seen = new ArrayList<>();
    Sink raising =
        v -> {
          seen.add(v);
          throw thrown;
        };
    Executable e = compile(new Try(AT, new Const(AT, result), new Const(AT, handler)), mode);
    assertThatExceptionOfType(FilterException.class)
        .as("run of try result catch handler into a raising sink")
        .isThrownBy(() -> e.run(new RunState(), new Object[0], new Object(), raising))
        .satisfies(
            f -> {
              assertThat(f).as("the instance").isSameAs(thrown);
              assertThat(f.depth()).as("its depth at the caller").isEqualTo(0);
            });
    assertThat(seen).as("what the sink saw: the body's result, no handler result").containsExactly(result);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void nestedTrysEachUnwrapTheirOwnWrap(CompileMode mode) {
    Object result = new Object();
    FilterException thrown = new FilterException(new Object());
    List<Object> seen = new ArrayList<>();
    Sink raising =
        v -> {
          seen.add(v);
          throw thrown;
        };
    Node nested = new Try(AT, new Try(AT, new Try(AT, new Const(AT, result), new Const(AT, "a")), new Const(AT, "b")), new Const(AT, "c"));
    Executable e = compile(nested, mode);
    assertThatExceptionOfType(FilterException.class)
        .as("run of three nested trys into a raising sink")
        .isThrownBy(() -> e.run(new RunState(), new Object[0], new Object(), raising))
        .satisfies(f -> assertThat(f.depth()).as("its depth at the caller").isEqualTo(0));
    assertThat(seen).as("what the sink saw").containsExactly(result);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aDownstreamErrorInsideAnOuterTryRunsTheOuterHandler(CompileMode mode) {
    // try ((try a catch inner) | raise) catch . : the inner Try lets the error through at depth 0 again, so the outer
    // Try catches it. The reference: control/try-inner-downstream-handler gives "o".
    Object a = new Object();
    Object inner = new Object();
    Node body = new Pipe(AT, new Try(AT, new Const(AT, a), new Const(AT, inner)), raise());
    assertThat(results(new Try(AT, body, new Identity(AT)), mode, new Object())).as("results").containsExactly(a);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aDownstreamErrorOfATryIsNotItsOwn(CompileMode mode) {
    // (try a catch handler) | raise : the reference's control/try-downstream-passes raises x.
    Object a = new Object();
    Node root = new Pipe(AT, new Try(AT, new Const(AT, a), new Const(AT, "handler")), raise());
    Executable e = compile(root, mode);
    assertThatExceptionOfType(FilterException.class)
        .as("run of (try a catch handler) | raise")
        .isThrownBy(() -> e.run(new RunState(), new Object[0], new Object(), v -> true))
        .satisfies(
            f -> {
              assertThat(f.value()).as("the value").isSameAs(a);
              assertThat(f.depth()).as("the depth").isEqualTo(0);
            });
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void anErrorOfTheHandlerPropagates(CompileMode mode) {
    // The reference: control/try-handler-raises raises b.
    Object b = new Object();
    Executable e = compile(new Try(AT, raise(new Object()), raise(b)), mode);
    assertThatExceptionOfType(FilterException.class)
        .as("run of try raise(a) catch raise(b)")
        .isThrownBy(() -> e.run(new RunState(), new Object[0], new Object(), v -> true))
        .satisfies(f -> assertThat(f.value()).as("the value").isSameAs(b));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void theBodyIsAbandonedAfterItsFirstError(CompileMode mode) {
    // The reference: control/try-abandons, [try (1, error(2), 3) catch .] gives [1,2].
    Object one = new Object();
    Object two = new Object();
    Node body = new Comma(AT, new Const(AT, one), new Comma(AT, raise(two), new Const(AT, new Object())));
    assertThat(results(new Try(AT, body, new Identity(AT)), mode, new Object())).as("results").containsExactly(one, two);
  }
}
