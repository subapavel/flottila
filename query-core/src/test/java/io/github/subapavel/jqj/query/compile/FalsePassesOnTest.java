package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Termination;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A false, a Break or a stop, passes out of AltBind's last alternative, a Collect's emit and a Reduce's update at once
 * (ADR-0008, ADR-0039), and the last alternative of an AltBind runs once, outside any catch (ADR-0038). Over a toy
 * format whose values are plain objects (founding §14), as in AltBindTest: {@code head} takes a list's first element and
 * raises on anything else, as the language's {@code .[0]} raises on a number.
 *
 * <p>Each expected value is jq-ref 1.8.2's output for the filter named beside it (#193, 2026-11-14).
 */
class FalsePassesOnTest {
  private static final Source AT = new Source(0, 1);

  /** The value slot of every chain here. */
  private static final int VALUE = 0;

  /** The label slot. */
  private static final int LABEL = 1;

  /** The group slot of every chain here. */
  private static final int GROUP = 2;

  /** A reduce's state slot. */
  private static final int STATE = 5;

  /** A reduce's {@code $x}. */
  private static final int X = 6;

  private static Executable compile(Node root, CompileMode mode) {
    return Compiler.compile(root, mode);
  }

  /** A run state in a run whose step budget is {@code steps}. */
  private static RunState running(long steps) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE);
    return state;
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  private static Comma comma(Node left, Node right) {
    return new Comma(AT, left, right);
  }

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  private static Intrinsic call(String name, CallForm form, Node... args) {
    return new Intrinsic(AT, new Operation(name, form), List.of(args));
  }

  /** The first element of a non-empty list; a FilterException on anything else. */
  private static Intrinsic head(Node arg) {
    return call(
        "head",
        (Fn1)
            (state, in, a) -> {
              if (a instanceof List<?> list && !list.isEmpty()) return list.get(0);
              throw new FilterException("head of a non-list");
            },
        arg);
  }

  private static Intrinsic id(Node arg) {
    return call("id", (Fn1) (state, in, a) -> a, arg);
  }

  /** A FilterException of {@code value}: the toy {@code error(value)}. */
  private static Intrinsic error(Object value) {
    return call(
        "error",
        (Fn0)
            (state, in) -> {
              throw new FilterException(value);
            });
  }

  /** {@code value as [$a] ?// $a | body}: the first alternative is {@code head}, the second the value itself. */
  private static AltBind asListOrValue(Node value, Node body) {
    Alternative list = new Alternative(new Bind(AT, head(var(VALUE)), 3, new Identity(AT)), List.of(3));
    Alternative plain = new Alternative(new Bind(AT, id(var(VALUE)), 4, new Identity(AT)), List.of(4));
    return new AltBind(AT, value, VALUE, List.of(list, plain), List.of(GROUP), body);
  }

  /** A sink that adds each result to {@code emitted} and never stops. */
  private static Sink collecting(List<Object> emitted) {
    return v -> {
      emitted.add(v);
      return true;
    };
  }

  private static List<Object> results(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    assertThat(compile(root, mode).run(new RunState(), new Object(), collecting(emitted))).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void theLastAlternativeRunsOnceAndItsErrorPropagatesAfterItsResults(CompileMode mode) {
    // jq-ref: [1] as [$a] ?// $a | ($a, error("x")) gives 1, [1], then the error "x" (rc 5): the first alternative's
    // error starts the second, and the second's error ends the run with its results given once.
    Object x = new Object();
    List<Object> in = List.of(x);
    AltBind root = asListOrValue(new Identity(AT), comma(var(GROUP), error("x")));
    List<Object> emitted = new ArrayList<>();
    FilterException e =
        assertThatExceptionOfType(FilterException.class)
            .as("run of [x] as [$a] ?// $a | ($a, error(\"x\"))")
            .isThrownBy(() -> compile(root, mode).run(new RunState(), in, collecting(emitted)))
            .actual();
    assertThat(e.value()).as("value of the error").isEqualTo("x");
    assertThat(emitted).as("results before the last alternative's error").containsExactly(x, in);

    // F-32: entering the second alternative is a step and so is entering each comma's right side, 3 steps in all, so a
    // budget of 3 steps ends with the error, not with the step budget.
    assertThatExceptionOfType(FilterException.class)
        .as("the same run with a budget of 3 steps")
        .isThrownBy(() -> compile(root, mode).run(running(3), in, collecting(new ArrayList<>())));
    // Witness: the 3 steps are all charged, so 2 end the run with the step budget.
    assertThatExceptionOfType(Termination.class)
        .as("the same run with a budget of 2 steps")
        .isThrownBy(() -> compile(root, mode).run(running(2), in, collecting(new ArrayList<>())));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakInTheLastAlternativeEndsTheValueToo(CompileMode mode) {
    // jq-ref: [label $f | (1,2) as [$a] ?// $a | ($a, break $f)] gives [1]: the value's second result never runs.
    Object p = new Object();
    Object q = new Object();
    Node root = new Label(AT, LABEL, asListOrValue(comma(c(p), c(q)), comma(var(GROUP), new Break(AT, LABEL))));
    assertThat(results(root, mode)).as("results of label | (p, q) as [$a] ?// $a | ($a, break)").containsExactly(p);

    // A host stop in the last alternative is the run's answer, and the value's second result never runs either.
    List<Object> emitted = new ArrayList<>();
    AltBind stopped = asListOrValue(comma(c(p), c(q)), var(GROUP));
    boolean answer =
        compile(stopped, mode)
            .run(
                new RunState(),
                new Object(),
                v -> {
                  emitted.add(v);
                  return false;
                });
    assertThat(answer).as("run's answer after a stop in the last alternative").isFalse();
    assertThat(emitted).as("results before the stop").containsExactly(p);
  }

  /** {@code [body]}: a list of the body's results. */
  private static Collect array(Node body) {
    return new Collect(AT, body, (elements, length) -> List.of(Arrays.copyOf(elements, length)));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakAfterACollectEndsWhatProducedIt(CompileMode mode) {
    // jq-ref: [label $f | ([1], [2]) | (., break $f)] gives [[1]]: the second array is never built.
    Node root = new Label(AT, LABEL, new Pipe(AT, comma(array(c(1)), array(c(2))), comma(new Identity(AT), new Break(AT, LABEL))));
    assertThat(results(root, mode)).as("results of label | ([1], [2]) | (., break)").containsExactly(List.of(1));

    // A host stop on the collected value is the run's answer.
    List<Object> emitted = new ArrayList<>();
    boolean answer =
        compile(comma(array(c(1)), array(c(2))), mode)
            .run(
                new RunState(),
                new Object(),
                v -> {
                  emitted.add(v);
                  return false;
                });
    assertThat(answer).as("run's answer after a stop on [1]").isFalse();
    assertThat(emitted).as("results before the stop").containsExactly(List.of(1));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakInAReduceUpdateEndsTheLabelsBody(CompileMode mode) {
    // jq-ref: [label $f | reduce (1,2) as $x (0; break $f), 5] gives []: the comma after the reduce never runs.
    Node reduce = new Reduce(AT, c(0), STATE, new Bind(AT, comma(c(1), c(2)), X, new Advance(AT, STATE, new Break(AT, LABEL))));
    Node root = new Label(AT, LABEL, comma(reduce, c(5)));
    assertThat(results(root, mode)).as("results of label | reduce (1,2) as $x (0; break), 5").isEmpty();
  }
}
