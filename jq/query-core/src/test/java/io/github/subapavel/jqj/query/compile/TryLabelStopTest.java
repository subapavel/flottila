package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.run.Collector;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A false passes a Try and a Collect at once (ADR-0008, ADR-0039), over plain objects (founding §14): a stop or a break
 * downstream of a Try's result ends its body, a stop in its handler ends the run, and a Collect whose body broke emits
 * nothing and gives the false to what follows it. The verifier checks a Try's body and handler as it checks any node.
 * Each expected order is the reference's, observed 2026-11-13 on the filter in its comment.
 */
class TryLabelStopTest {
  private static final Source AT = new Source(0, 1);

  /** Collects the elements into a list. */
  private static final Collector LIST = (elements, length) -> List.of(Arrays.copyOf(elements, length));

  /** An Fn0 that raises its input as the error value. */
  private static final Fn0 RAISE = (state, in) -> {
    throw new FilterException(in);
  };

  private static Executable compile(Node root, CompileMode mode) {
    return Compiler.compile(root, 0, mode);
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  /** {@code value | raise}: an error of {@code value}. */
  private static Node raise(Object value) {
    return new Pipe(AT, c(value), new Intrinsic(AT, new Operation("raise", RAISE), List.of()));
  }

  /** The results of {@code root} into a sink that never stops; asserts the run's answer {@code ranOut}. */
  private static List<Object> results(Node root, CompileMode mode, boolean ranOut) {
    List<Object> emitted = new ArrayList<>();
    Sink all =
        v -> {
          emitted.add(v);
          return true;
        };
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), all)).as("run's answer").isEqualTo(ranOut);
    return emitted;
  }

  /** The results of {@code root} into a sink that stops at its first result; asserts that the run reports the stop. */
  private static List<Object> firstOnly(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    Sink first =
        v -> {
          emitted.add(v);
          return false;
        };
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), first)).as("run's answer after the sink stops").isFalse();
    return emitted;
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aStopDownstreamOfATryResultEndsItsBody(CompileMode mode) {
    Object one = new Object();
    Node root = new Try(AT, new Comma(AT, c(one), c(new Object())), c(new Object()));
    assertThat(firstOnly(root, mode)).as("results of try (1, 2) catch h before the stop").containsExactly(one);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aStopInTheHandlerEndsTheRun(CompileMode mode) {
    Object one = new Object();
    Node root = new Try(AT, raise(new Object()), new Comma(AT, c(one), c(new Object())));
    assertThat(firstOnly(root, mode)).as("results of try raise catch (1, 2) before the stop").containsExactly(one);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakDownstreamOfATryResultEndsItsBody(CompileMode mode) {
    // The reference: [label $l | (try (1, 2)) | ., break $l] gives [1].
    Node body = new Pipe(AT, new Try(AT, new Comma(AT, c(1), c(2)), c(3)), new Comma(AT, new Identity(AT), new Break(AT, 0)));
    assertThat(results(new Label(AT, 0, body), mode, true)).as("results").containsExactly(1);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakInTheHandlerEndsTheLabel(CompileMode mode) {
    // The reference: [label $l | try error("x") catch (1, break $l, 2)] gives [1].
    Node handler = new Comma(AT, c(1), new Comma(AT, new Break(AT, 0), c(2)));
    assertThat(results(new Label(AT, 0, new Try(AT, raise("x"), handler)), mode, true)).as("results").containsExactly(1);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aCollectThatBrokeGivesTheFalseToWhatFollows(CompileMode mode) {
    // The reference: [label $l | [1, break $l], 2] gives []: the comma after the Collect does not run its right side.
    Node collect = new Collect(AT, new Comma(AT, c(1), new Break(AT, 0)), LIST);
    assertThat(results(new Label(AT, 0, new Comma(AT, collect, c(2))), mode, true)).as("results").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void rejectsABreakOfNoLabelInATryBodyOrHandler(CompileMode mode) {
    // Witness: the same Trys inside a Label of slot 0 compile (LabelVerifierTest's rule).
    assertThat(results(new Label(AT, 0, new Try(AT, new Break(AT, 0), c(1))), mode, true)).as("results of label 0 | try break 0 catch 1").isEmpty();
    assertThat(results(new Label(AT, 0, new Try(AT, raise(1), new Break(AT, 0))), mode, true)).as("results of label 0 | try raise catch break 0").isEmpty();
    assertThatIllegalStateException().as("compile of try break 0 catch 1").isThrownBy(() -> compile(new Try(AT, new Break(AT, 0), c(1)), mode));
    assertThatIllegalStateException().as("compile of try raise catch break 0").isThrownBy(() -> compile(new Try(AT, raise(1), new Break(AT, 0)), mode));
  }
}
