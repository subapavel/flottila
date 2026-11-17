package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The state-slot rule of ADR-0041 at the IR seam: a state slot is bound by one Reduce or Foreach, set by that node and
 * by its one Advance inside {@code each}, and read by no Var. A broken rule is an IllegalStateException from compile,
 * as for CommaBindTest and #67's IrVerifierTest. Shapes as in ReduceOpTest.
 */
class StateSlotVerifierTest {
  private static final Source AT = new Source(0, 1);

  private static Executable compile(Node root, CompileMode mode) {
    return Compiler.compile(root, 0, mode);
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  private static Advance advance(int slot) {
    return new Advance(AT, slot, new Identity(AT));
  }

  private static List<Object> results(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    assertThat(
            compile(root, mode)
                .run(
                    new RunState(),
                    new Object[0],
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
  void theLoweredFormsCompile(CompileMode mode) {
    // Witnesses for every rejection below: reduce 1 as $1 (7; .), [foreach 1 as $1 (7; $1; .)], and two sibling
    // loops on their own state slots.
    Node reduce = new Reduce(AT, c(7), 0, new Bind(AT, c(1), 1, advance(0)));
    assertThat(results(reduce, mode)).as("results of reduce 1 as $1 (7; .)").containsExactly(7);
    Node foreach = new Foreach(AT, c(7), 0, new Bind(AT, c(1), 1, new Pipe(AT, new Advance(AT, 0, new Var(AT, 1)), new Identity(AT))));
    assertThat(results(foreach, mode)).as("results of foreach 1 as $1 (7; $1; .)").containsExactly(1);
    Node siblings = new Comma(AT, new Reduce(AT, c(7), 0, advance(0)), new Foreach(AT, c(8), 1, advance(1)));
    assertThat(results(siblings, mode)).as("results of two sibling loops").containsExactly(7, 8);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsAnAdvanceOfNoLoop(CompileMode mode) {
    assertThatIllegalStateException().as("compile of advance 0 alone").isThrownBy(() -> compile(advance(0), mode));
    // An Advance whose slot is a Bind's.
    Node bound = new Bind(AT, c(1), 0, advance(0));
    assertThatIllegalStateException().as("compile of 1 as $0 | advance 0").isThrownBy(() -> compile(bound, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsAnAdvanceOutsideItsLoopsEach(CompileMode mode) {
    // The loop's each has no Advance of its own; the one Advance of its slot comes after the loop.
    Node after = new Comma(AT, new Reduce(AT, c(7), 0, new Identity(AT)), advance(0));
    assertThatIllegalStateException().as("compile of (reduce 0: init 7, each .), advance 0").isThrownBy(() -> compile(after, mode));
    // The Advance of slot 0 sits in another loop's each.
    Node other = new Comma(AT, new Reduce(AT, c(7), 0, new Identity(AT)), new Foreach(AT, c(8), 1, new Comma(AT, advance(1), advance(0))));
    assertThatIllegalStateException().as("compile of an advance 0 in loop 1's each").isThrownBy(() -> compile(other, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsAnAdvanceInTheInit(CompileMode mode) {
    Node reduce = new Reduce(AT, advance(0), 0, new Identity(AT));
    assertThatIllegalStateException().as("compile of reduce 0 with advance 0 as its init").isThrownBy(() -> compile(reduce, mode));
    Node foreach = new Foreach(AT, new Pipe(AT, c(7), advance(0)), 0, new Identity(AT));
    assertThatIllegalStateException().as("compile of foreach 0 with 7 | advance 0 as its init").isThrownBy(() -> compile(foreach, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsASecondAdvanceForTheSameState(CompileMode mode) {
    Node reduce = new Reduce(AT, c(7), 0, new Comma(AT, advance(0), advance(0)));
    assertThatIllegalStateException().as("compile of reduce 0 with each advance 0, advance 0").isThrownBy(() -> compile(reduce, mode));
    // One nested in the other's update.
    Node nested = new Foreach(AT, c(7), 0, new Advance(AT, 0, advance(0)));
    assertThatIllegalStateException().as("compile of foreach 0 with each advance 0 (advance 0)").isThrownBy(() -> compile(nested, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsAVarOfAStateSlot(CompileMode mode) {
    Node inUpdate = new Reduce(AT, c(7), 0, new Advance(AT, 0, new Var(AT, 0)));
    assertThatIllegalStateException().as("compile of reduce 0 with update $0").isThrownBy(() -> compile(inUpdate, mode));
    Node inEach = new Foreach(AT, c(7), 0, new Pipe(AT, advance(0), new Var(AT, 0)));
    assertThatIllegalStateException().as("compile of foreach 0 with each advance 0 | $0").isThrownBy(() -> compile(inEach, mode));
    Node inInit = new Reduce(AT, new Var(AT, 0), 0, advance(0));
    assertThatIllegalStateException().as("compile of reduce 0 with init $0").isThrownBy(() -> compile(inInit, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsAStateSlotBoundTwice(CompileMode mode) {
    Node byBind = new Bind(AT, c(1), 0, new Reduce(AT, c(7), 0, advance(0)));
    assertThatIllegalStateException().as("compile of 1 as $0 | reduce 0").isThrownBy(() -> compile(byBind, mode));
    Node byLoops = new Comma(AT, new Reduce(AT, c(7), 0, advance(0)), new Foreach(AT, c(8), 0, advance(0)));
    assertThatIllegalStateException().as("compile of reduce 0, foreach 0").isThrownBy(() -> compile(byLoops, mode));
  }
}
