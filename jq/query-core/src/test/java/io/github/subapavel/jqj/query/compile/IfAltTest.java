package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0037 at the IR seam, over a toy format whose values are plain objects and whose truth holds for every value but
 * {@link #NO} (this module never looks inside a value, founding §14): {@code If(cond, then, otherwise, truth)} runs one
 * branch per result of its condition, on the If's own input; {@code Alt(left, right, truth)} emits the left side's
 * results the truth holds for and runs the right side only when there was none. Neither charges a step, a stop comes
 * back as false at once, and the verifier walks every operand of both, so a node used twice in them is rejected.
 */
class IfAltTest {
  private static final Source AT = new Source(0, 1);

  /** The one value the toy truth does not hold for. */
  private static final Object NO = new Object();

  private static final Truth TOY = v -> v != NO;

  private static Executable compile(Node root) {
    return Compiler.compile(root, CompileMode.OPTIMISED);
  }

  /** A run state in a run whose step budget is {@code steps}: its next tick past them ends the run (ADR-0030). */
  private static RunState running(long steps) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE);
    return state;
  }

  /** The results of {@code root} on {@code in} under {@code state}, the sink stopping after {@code stopAfter} results. */
  private static List<Object> results(Node root, RunState state, Object in, int stopAfter, boolean expectedAnswer) {
    List<Object> emitted = new ArrayList<>();
    boolean answer =
        compile(root)
            .run(
                state,
                in,
                v -> {
                  emitted.add(v);
                  return emitted.size() < stopAfter;
                });
    assertThat(answer).as("run's answer, the sink stopping after %s results", stopAfter).isEqualTo(expectedAnswer);
    return emitted;
  }

  /** The results of {@code root} on {@code in} with no step to spare; the sink never stops. */
  private static List<Object> results(Node root, Object in) {
    return results(root, running(0), in, Integer.MAX_VALUE, true);
  }

  private static Const constant(Object value) {
    return new Const(AT, value);
  }

  private static Comma comma(Node left, Node right) {
    return new Comma(AT, left, right);
  }

  /** An operation that gives {@code [name, its input]}. */
  private static Intrinsic tagged(String name) {
    return new Intrinsic(AT, new Operation(name, (Fn0) (state, in) -> List.of(name, in)), List.of());
  }

  /** An operation that fails the test if it runs. */
  private static Intrinsic never() {
    return new Intrinsic(
        AT,
        new Operation(
            "never",
            (Fn0)
                (state, in) -> {
                  throw new AssertionError("a side ran that must not");
                }),
        List.of());
  }

  /** An operation with no result. */
  private static Intrinsic none() {
    return new Intrinsic(AT, new Operation("none", (Fn0) (state, in) -> CallForm.NONE), List.of());
  }

  @Test
  void ifRunsOneBranchPerResultOfItsConditionOnItsOwnInput() {
    Object in = new Object();
    Object yes = new Object();
    // The comma's one step is the only one, so a budget of 1 shows the If charges none.
    If twice = new If(AT, comma(constant(yes), constant(NO)), tagged("then"), tagged("else"), TOY);
    assertThat(results(twice, running(1), in, Integer.MAX_VALUE, true)).as("results of if (yes, NO) then t else e").containsExactly(List.of("then", in), List.of("else", in));
    If single = new If(AT, constant(NO), tagged("then"), tagged("else"), TOY);
    assertThat(results(single, in)).as("results of if NO then t else e").containsExactly(List.of("else", in));
    assertThat(results(new If(AT, none(), never(), never(), TOY), in)).as("results of if none then never else never").isEmpty();
  }

  @Test
  void aStopInABranchEndsTheCondition() {
    Object a = new Object();
    // The condition's second result would run the never branch.
    If stopped = new If(AT, comma(constant(a), constant(NO)), constant(a), never(), TOY);
    assertThat(results(stopped, running(0), new Object(), 1, false)).as("results of if (a, NO) then a else never, stopped at a").containsExactly(a);
    // Witness: the same If with a sink that never stops runs both.
    If both = new If(AT, comma(constant(a), constant(NO)), constant(a), constant(NO), TOY);
    assertThat(results(both, running(1), new Object(), Integer.MAX_VALUE, true)).as("results of if (a, NO) then a else NO").containsExactly(a, NO);
  }

  @Test
  void altEmitsTheTruthyLeftResultsAndSkipsTheRight() {
    Object a = new Object();
    Object b = new Object();
    Alt alt = new Alt(AT, comma(comma(constant(a), constant(NO)), constant(b)), never(), TOY);
    assertThat(results(alt, running(2), new Object(), Integer.MAX_VALUE, true)).as("results of (a, NO, b) // never").containsExactly(a, b);
  }

  @Test
  void altRunsTheRightOnItsInputWhenNoLeftResultIsTruthy() {
    Object in = new Object();
    assertThat(results(new Alt(AT, constant(NO), tagged("right"), TOY), in)).as("results of NO // right").containsExactly(List.of("right", in));
    assertThat(results(new Alt(AT, none(), tagged("right"), TOY), in)).as("results of none // right").containsExactly(List.of("right", in));
    // The right side's results pass whatever the truth says of them.
    assertThat(results(new Alt(AT, none(), constant(NO), TOY), in)).as("results of none // NO").containsExactly(NO);
  }

  @Test
  void aStopEndsTheAlt() {
    Object a = new Object();
    Object b = new Object();
    // A stop on the left: no second left result, and no right side.
    Alt left = new Alt(AT, comma(constant(a), never()), never(), TOY);
    assertThat(results(left, running(0), new Object(), 1, false)).as("results of (a, never) // never, stopped at a").containsExactly(a);
    // A stop on the right comes back as false too.
    Alt right = new Alt(AT, constant(NO), comma(constant(a), constant(b)), TOY);
    assertThat(results(right, running(0), new Object(), 1, false)).as("results of NO // (a, b), stopped at a").containsExactly(a);
  }

  @Test
  void theVerifierWalksEveryOperandOfIfAndAlt() {
    Const shared = constant(new Object());
    List<Node> twice =
        List.of(
            new If(AT, shared, shared, constant(NO), TOY),
            new If(AT, constant(NO), shared, shared, TOY),
            new If(AT, shared, constant(NO), shared, TOY),
            new Alt(AT, shared, shared, TOY));
    for (Node node : twice) {
      assertThatIllegalStateException().as("compile of %s, one node twice", node).isThrownBy(() -> compile(node));
    }
    // Each operand is in the scope of the Binds around the node, and its own Binds count as slots.
    Fn1 id = (state, in, v) -> v;
    Object x = new Object();
    Node bound = new Bind(AT, constant(x), 0, new If(AT, new Var(AT, 0), new Bind(AT, constant(NO), 1, new Var(AT, 1)), never(), TOY));
    assertThat(results(bound, new Object())).as("results of x as $0 | if $0 then (NO as $1 | $1) else never").containsExactly(NO);
    Node alt = new Bind(AT, constant(x), 0, new Alt(AT, constant(NO), new Bind(AT, constant(x), 1, new Intrinsic(AT, new Operation("id", id), List.of(new Var(AT, 1)))), TOY));
    assertThat(results(alt, new Object())).as("results of x as $0 | NO // (x as $1 | id($1))").containsExactly(x);
  }
}
