package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The steps of a call at the public API (#107 "Call kinds", ADR-0045 #107 amendment "Frames", ADR-0040 "Cost"): each
 * Call and each real-closure invocation ticks one step, and a break to a label a lifted function captured ticks nothing
 * beyond the Break's own cost, none (ADR-0039 "Cost"; ADR-0045 #107 rejected a label passed as a Break closure because
 * a break would then tick by whether its function was lifted). Each count is a step boundary: the least step budget
 * under which the run completes, found by bisection over fresh engines; one step fewer ends it with
 * {@code JqTermination(BUDGET, STEPS)} (ADR-0030). Each pair of filters runs the same nodes but for the calls it
 * compares, so whatever else ticks cancels out and the difference of the boundaries is the calls' ticks. Every run is
 * on null with a filter that starts from a literal, so no read ticks. Every expected result is jq-ref 1.8.2's,
 * recorded in diff/calls.rec under the id each assertion names.
 */
class CallStepsTest {
  private static final long CEILING = 1 << 16;

  /** F on 0: main's call into the recursive group is one Call, whose body takes its then branch (calls/one-call-on-0). */
  private static final String ONE_CALL_ON_0 = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; 0 | f";

  /** F's body on 0 written out, with no call: the nodes of the one Call's run (calls/no-call-on-0). */
  private static final String NO_CALL_ON_0 = "0 | if . == 0 then 0 else (. - 1 | 0) + 1 end";

  /** F on 2: three live Calls (calls/three-calls-on-2). */
  private static final String THREE_CALLS_ON_2 = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; 2 | f";

  /** F's body on 2 written out three levels deep, with no call (calls/no-call-on-2). */
  private static final String NO_CALL_ON_2 =
      "2 | if . == 0 then 0 else (. - 1 | if . == 0 then 0 else (. - 1 | if . == 0 then 0 else (. - 1 | 0) + 1 end) + 1 end) + 1 end";

  /** The result texts of {@code filter} on null with {@code steps} steps in {@code mode}, or null when STEPS ended it. */
  private static List<String> run(String filter, long steps, CompileMode mode) {
    JqEngine engine = steps(steps, mode);
    Results results = new Results();
    try {
      assertThat(engine.compile(filter).run(JqNull.NULL, NONE, results, engine.newWorkspace())).as("run's answer for %s with %d steps", filter, steps).isTrue();
    } catch (JqTermination t) {
      assertThat(t.budget()).as("budget that ended %s with %d steps", filter, steps).isEqualTo(Budget.STEPS);
      return null;
    }
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /**
   * The step boundary of {@code filter} on null in {@code mode}: with it the run completes giving {@code texts}, and
   * with one step fewer it ends with STEPS.
   */
  private static long boundary(String filter, CompileMode mode, String... texts) {
    assertThat(run(filter, CEILING, mode)).as("results of %s with %d steps", filter, CEILING).containsExactly(texts);
    long lo = 0;
    long hi = CEILING;
    while (lo < hi) {
      long mid = (lo + hi) >>> 1;
      if (run(filter, mid, mode) != null) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    assertThat(run(filter, lo, mode)).as("results of %s with its boundary of %d steps", filter, lo).containsExactly(texts);
    if (lo > 0) {
      long under = lo - 1;
      JqEngine engine = steps(under, mode);
      assertBudget(() -> engine.compile(filter).run(JqNull.NULL, NONE, new Results(), engine.newWorkspace()), Budget.STEPS, filter + " with " + under + " steps");
    }
    return lo;
  }

  @Test
  void aCallTicksOneStep() {
    long none = boundary(NO_CALL_ON_0, CompileMode.OPTIMISED, "0");
    long one = boundary(ONE_CALL_ON_0, CompileMode.OPTIMISED, "0");
    assertThat(one - none).as("the boundary of F on 0 (one Call) against the same nodes without it").isEqualTo(1);
    // Each live Call ticks once: three nested Calls, three steps.
    long unrolled = boundary(NO_CALL_ON_2, CompileMode.OPTIMISED, "2");
    long three = boundary(THREE_CALLS_ON_2, CompileMode.OPTIMISED, "2");
    assertThat(three - unrolled).as("the boundary of F on 2 (three Calls) against the same nodes without them").isEqualTo(3);
  }

  /**
   * The conservative compile keeps r's closure real (ADR-0018: no specialisation). Where r's base runs its closure f,
   * the invocation ticks one step against the same r whose base is the closure's body 5. A passed-on closure is not
   * wrapped, so r at depth 2 still invokes it once: one step there too, not three (calls/closure-run-at-depth-0,
   * calls/closure-never-run-at-depth-0, calls/closure-run-at-depth-2, calls/closure-never-run-at-depth-2: 5 each).
   */
  @Test
  void aRealClosureCallTicksOneStep() {
    for (int depth : new int[] {0, 2}) {
      long body = boundary("def r(f): if . == 0 then 5 else (. - 1 | r(f)) end; " + depth + " | r(5)", CompileMode.CONSERVATIVE, "5");
      long invoked = boundary("def r(f): if . == 0 then f else (. - 1 | r(f)) end; " + depth + " | r(5)", CompileMode.CONSERVATIVE, "5");
      assertThat(invoked - body).as("the boundary of r invoking its closure at depth %d against r giving its body", depth).isEqualTo(1);
    }
  }

  /**
   * f is defined inside the label, so it captures the label's token as a value (ADR-0045 #107 "Parameters"). Its break
   * after four Calls takes the steps of empty there (ADR-0039: neither ticks; ControlStepsTest pins break against
   * empty without a call), so the two boundaries are equal (calls/break-to-a-captured-label and
   * calls/empty-instead-of-a-break: [] each).
   */
  @Test
  void aBreakToACapturedLabelTicksNothing() {
    long broken = boundary("[label $l | def f: if . > 2 then break $l else (. + 1 | f) end; 0 | f]", CompileMode.OPTIMISED, "[]");
    long empty = boundary("[label $l | def f: if . > 2 then empty else (. + 1 | f) end; 0 | f]", CompileMode.OPTIMISED, "[]");
    assertThat(broken).as("the boundary of a break to a captured label against empty in its place").isEqualTo(empty);
  }
}
