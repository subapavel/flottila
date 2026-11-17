package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.CallRuns.F;
import static io.github.subapavel.jqj.CallRuns.STACK;
import static io.github.subapavel.jqj.CallRuns.onStack;
import static io.github.subapavel.jqj.CallRuns.texts;
import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.recursionDepth;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * The recursion-depth budget at the public API (#107 "Depth budget", ADR-0046 "The recursion-depth budget counts",
 * ADR-0045 #107 amendment "Frames"): it counts one per live Call and one per live real-closure invocation, a call the
 * Resolver inlined counts nothing, and a call leaves the count however it ends, by its results running out, an error
 * or a break. Going over ends the run with {@code JqTermination(BUDGET, RECURSION_DEPTH)}, exit code 5 and the message
 * "Recursion-depth budget exceeded"; a termination is no error, so no {@code try} catches it (ADR-0008). Main is no
 * activation: input n of {@link CallRuns#F} holds n + 1 Calls at its deepest, so a budget of 100 takes 99 and ends 100.
 * Each run is on a thread of {@link CallRuns#STACK}. Every expected result is jq-ref 1.8.2's, recorded in
 * diff/calls.rec under the id each assertion names.
 */
class RecursionDepthTest {

  /** {@code [range(3) | try (n | f) catch .]} with an f that raises "x" at 0, under F's recursion otherwise. */
  private static String raisingAt(int n) {
    return "def f: if . == 0 then error(\"x\") else (. - 1 | f) + 1 end; [range(3) | try (" + n + " | f) catch .]";
  }

  /** Three runs of an f that breaks to a label it captures at 0 after n levels, each followed by its range value. */
  private static String breakingAt(int n) {
    return "[range(3) | (label $l | def f: if . == 0 then break $l else (. - 1 | f) + 1 end; " + n + " | f), .]";
  }

  @Test
  void recursionDepthBudgetEndsTheRun() {
    onStack(
        STACK,
        () -> {
          JqEngine engine = recursionDepth(100, CompileMode.OPTIMISED);
          JqFilter f = engine.compile(F);
          Workspace workspace = engine.newWorkspace();
          // calls/depth-99: 100 live Calls at the deepest, the whole budget.
          assertThat(texts(f, workspace, "99")).as("results of F on 99 with a depth of 100").containsExactly("99");

          // 101 live Calls: the 101st ends the run before anything is emitted.
          JqValue hundred = read(workspace, "100".getBytes(US_ASCII)).root();
          Results none = new Results();
          assertBudget(() -> f.run(hundred, NONE, none, workspace), Budget.RECURSION_DEPTH, "F on 100 with a depth of 100");
          assertThat(none.values).as("results of F on 100 with a depth of 100").isEmpty();

          // The Workspace runs again, and its count starts afresh: 99 takes the whole budget once more.
          assertThat(texts(f, workspace, "99")).as("results of F on 99 after the termination").containsExactly("99");

          // Witness: one more level of budget takes 100 (calls/depth-100).
          JqEngine wider = recursionDepth(101, CompileMode.OPTIMISED);
          assertThat(texts(wider.compile(F), wider.newWorkspace(), "100")).as("results of F on 100 with a depth of 101").containsExactly("100");
        });
  }

  /**
   * A budget of 0 holds no activation: the first Call ends the run, and a filter whose every call is inlined runs
   * (calls/one-call-on-0 gives 0 on jq-ref, calls/inlined-calls-only gives [2,2]).
   */
  @Test
  void aBudgetOfZeroEndsTheFirstCall() {
    JqEngine engine = recursionDepth(0, CompileMode.OPTIMISED);
    Workspace workspace = engine.newWorkspace();
    assertBudget(() -> texts(engine.compile(F), workspace, "0"), Budget.RECURSION_DEPTH, "F on 0 with a depth of 0");
    assertThat(texts(engine.compile("def g: . + 1; def h: g | g; [h, h]"), workspace, "0")).as("results of inlined calls only with a depth of 0").containsExactly("[2,2]");
  }

  /**
   * A closure parameter passed on without wrapping is one activation when it runs, however deep it was passed
   * (ADR-0045 #107: the caller passes a ParamCall argument on without wrapping it). In the conservative compile the
   * closure stays real (ADR-0018: no specialisation). On 98, r holds 99 Calls and its closure's invocation one more:
   * 100, the budget; on 99, 101. jq-ref: 0 on both (calls/passed-on-closure-98, calls/passed-on-closure-99).
   */
  @Test
  void aPassedOnClosureIsOneActivation() {
    onStack(
        STACK,
        () -> {
          JqEngine engine = recursionDepth(100, CompileMode.CONSERVATIVE);
          JqFilter r = engine.compile("def r(f): if . == 0 then f else (. - 1 | r(f)) + 0 end; r(0)");
          Workspace workspace = engine.newWorkspace();
          assertThat(texts(r, workspace, "98")).as("results of r(0) on 98 with a depth of 100").containsExactly("0");
          // The closure's invocation counts: 100 Calls alone would fit.
          assertBudget(() -> texts(r, workspace, "99"), Budget.RECURSION_DEPTH, "r(0) on 99 with a depth of 100");
        });
  }

  /**
   * An inlined call holds no frame, so it counts nothing (ADR-0046): w is inlined around each recursive call, and F's
   * 99 levels still fit a budget of 100 (calls/inlined-wrapper-around-a-recursive-call gives 99 on jq-ref). If w
   * counted, 99 would hold 199 activations.
   */
  @Test
  void anInlinedCallCountsNothing() {
    onStack(
        STACK,
        () -> {
          JqEngine engine = recursionDepth(100, CompileMode.OPTIMISED);
          JqFilter wrapped = engine.compile("def w(g): g; def f: if . == 0 then 0 else w(. - 1 | f) + 1 end; f");
          Workspace workspace = engine.newWorkspace();
          assertThat(texts(wrapped, workspace, "99")).as("results of the wrapped F on 99 with a depth of 100").containsExactly("99");
          // Witness: the budget is live for this filter.
          assertBudget(() -> texts(wrapped, workspace, "100"), Budget.RECURSION_DEPTH, "the wrapped F on 100 with a depth of 100");
        });
  }

  /**
   * A Call counts only while it is live: two runs of 99 levels one after the other each fit a budget of 100
   * (calls/two-deep-calls-in-sequence gives [99,99] on jq-ref).
   */
  @Test
  void aCallThatReturnedCountsNothing() {
    onStack(
        STACK,
        () -> {
          JqEngine engine = recursionDepth(100, CompileMode.OPTIMISED);
          String twice = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; [(99 | f), (99 | f)]";
          assertThat(texts(engine.compile(twice), engine.newWorkspace(), "null")).as("results of two runs of F on 99 with a depth of 100").containsExactly("[99,99]");
        });
  }

  /**
   * An error that leaves a call ends its activation (ADR-0045 #107: it leaves the count however it ends): three caught
   * errors out of 100 live Calls each fit a budget of 100 (calls/error-leaving-deep-calls gives ["x","x","x"] on jq-ref).
   * If the count kept the first run's 100, the second would end the run.
   */
  @Test
  void anErrorLeavingACallEndsItsActivation() {
    onStack(
        STACK,
        () -> {
          JqEngine engine = recursionDepth(100, CompileMode.OPTIMISED);
          Workspace workspace = engine.newWorkspace();
          assertThat(texts(engine.compile(raisingAt(99)), workspace, "null")).as("results of three caught errors at 99 levels").containsExactly("[\"x\",\"x\",\"x\"]");
          // Witness: one level more ends the run, and the try does not catch the termination.
          assertBudget(() -> texts(engine.compile(raisingAt(100)), workspace, "null"), Budget.RECURSION_DEPTH, "three caught errors at 100 levels");
        });
  }

  /**
   * A break that leaves a call ends its activation: three breaks out of 100 live Calls each fit a budget of 100, and
   * each run's range value follows (calls/break-leaving-deep-calls gives [0,1,2] on jq-ref).
   */
  @Test
  void aBreakLeavingACallEndsItsActivation() {
    onStack(
        STACK,
        () -> {
          JqEngine engine = recursionDepth(100, CompileMode.OPTIMISED);
          Workspace workspace = engine.newWorkspace();
          assertThat(texts(engine.compile(breakingAt(99)), workspace, "null")).as("results of three breaks at 99 levels").containsExactly("[0,1,2]");
          // Witness: one level more ends the run.
          assertBudget(() -> texts(engine.compile(breakingAt(100)), workspace, "null"), Budget.RECURSION_DEPTH, "three breaks at 100 levels");
        });
  }
}
