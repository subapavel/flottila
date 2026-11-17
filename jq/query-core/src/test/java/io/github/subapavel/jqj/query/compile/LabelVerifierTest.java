package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Collector;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The slot rules of Label and Break (ADR-0039) at the IR seam, over plain objects (founding §14), and their run: a
 * Break's slot is a Label's, and it sits inside that Label's body; a Var never reads a Label's slot, and a Break never
 * names a Bind's. A broken rule is an IllegalStateException from compile, as for IrVerifierTest (#67) and CommaBindTest.
 * A Break returns false with its token, every node passes a false on at once, the Label whose token it is returns true,
 * and a host stop (false with no token) passes every Label. No catch set holds a break; Collect emits nothing after a
 * false.
 *
 * <p>This class holds #68's IrVerifierTest additions in a file of its own, so it does not collide with #67's
 * IrVerifierTest. Shapes the test-author fixed from ADR-0039 (no draft text reached the red): {@code Label(Source, int
 * slot, Node body)}, {@code Break(Source, int slot)} and {@code Try(Source, Node body, Node handler)} in {@code
 * query.ir}; compile and run take #67's parameters.
 */
class LabelVerifierTest {
  private static final Source AT = new Source(0, 1);

  /** Collects the elements into a list. */
  private static final Collector LIST = (elements, length) -> List.of(Arrays.copyOf(elements, length));

  private static Executable compile(Node root, CompileMode mode) {
    return Compiler.compile(root, 0, mode);
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  /** The results of {@code root}; the sink never stops, and the run must report that the results ran out. */
  private static List<Object> results(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    Sink all =
        v -> {
          emitted.add(v);
          return true;
        };
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), all)).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakInsideItsLabelCompilesAndEndsTheBody(CompileMode mode) {
    // Witness for every rejection below. The reference: [label $l | 1, break $l, 2] gives [1] (observed 2026-11-13).
    Object one = new Object();
    Node root = new Label(AT, 0, new Comma(AT, c(one), new Comma(AT, new Break(AT, 0), c(new Object()))));
    assertThat(results(root, mode)).as("results of label 0 | 1, break 0, 2").containsExactly(one);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsABreakOutsideItsLabel(CompileMode mode) {
    // (label 0 | 1), break 0: the slot is a Label's, but the Break is not in its body.
    Node root = new Comma(AT, new Label(AT, 0, c(1)), new Break(AT, 0));
    assertThatIllegalStateException().as("compile of (label 0 | 1), break 0").isThrownBy(() -> compile(root, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsABreakOfNoLabel(CompileMode mode) {
    assertThatIllegalStateException().as("compile of break 0 alone").isThrownBy(() -> compile(new Break(AT, 0), mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsAVarOnALabelSlot(CompileMode mode) {
    Node root = new Label(AT, 0, new Comma(AT, new Var(AT, 0), new Break(AT, 0)));
    assertThatIllegalStateException().as("compile of label 0 | $0, break 0").isThrownBy(() -> compile(root, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsABreakOnABindSlot(CompileMode mode) {
    // Witness: the same Bind with a Var in its body compiles (CommaBindTest's rule).
    assertThat(results(new Bind(AT, c(7), 0, new Var(AT, 0)), mode)).as("results of 7 as $0 | $0").containsExactly(7);
    Node root = new Bind(AT, c(7), 0, new Break(AT, 0));
    assertThatIllegalStateException().as("compile of 7 as $0 | break 0").isThrownBy(() -> compile(root, mode));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakToTheOuterLabelEndsBoth(CompileMode mode) {
    // The reference: [(label $a | label $b | 1, break $a, 2), 3] gives [1,3] (control/label-break-outer).
    Node inner = new Label(AT, 1, new Comma(AT, c(1), new Comma(AT, new Break(AT, 0), c(2))));
    Node root = new Comma(AT, new Label(AT, 0, new Comma(AT, inner, c(4))), c(3));
    assertThat(results(root, mode)).as("results").containsExactly(1, 3);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakToTheInnerLabelEndsOnlyIt(CompileMode mode) {
    // The reference: [(label $a | (label $b | 1, break $b, 2), 3), 4] gives [1,3,4] (control/label-break-inner).
    Node inner = new Label(AT, 1, new Comma(AT, c(1), new Comma(AT, new Break(AT, 1), c(2))));
    Node root = new Comma(AT, new Label(AT, 0, new Comma(AT, inner, c(3))), c(4));
    assertThat(results(root, mode)).as("results").containsExactly(1, 3, 4);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakPassesATryWithoutItsHandler(CompileMode mode) {
    // No catch set holds a break (DEVIATIONS "A break is not an error", row 1: this project gives [1]).
    Object handler = new Object();
    Node root = new Label(AT, 0, new Comma(AT, c(1), new Comma(AT, new Try(AT, new Break(AT, 0), c(handler)), c(2))));
    assertThat(results(root, mode)).as("results of label 0 | 1, (try break 0 catch h), 2").containsExactly(1);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aCollectEmitsNothingAfterABreak(CompileMode mode) {
    // The reference: [label $l | [1, break $l]] gives [] (control/label-collect-break; ADR-0039 C3).
    Node root = new Label(AT, 0, new Collect(AT, new Comma(AT, c(1), new Break(AT, 0)), LIST));
    assertThat(results(root, mode)).as("results of label 0 | [1, break 0]").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aHostStopPassesTheLabel(CompileMode mode) {
    // A stop is false with no token: the Label passes it on, so run reports the stop and the body emits no more.
    List<Object> emitted = new ArrayList<>();
    Sink first =
        v -> {
          emitted.add(v);
          return false;
        };
    Node root = new Label(AT, 0, new Comma(AT, c(1), new Comma(AT, c(2), new Break(AT, 0))));
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), first)).as("run's answer after the sink stops").isFalse();
    assertThat(emitted).as("results before the stop").containsExactly(1);
  }
}
