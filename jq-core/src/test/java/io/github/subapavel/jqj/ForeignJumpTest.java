package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A jump is taken only by the activation it was left pending from (ADR-0047 #118, "Where a jump is taken"). On the
 * trampoline the right side of a Pipe runs inside the left side's emit, so when the left side is a call that is at most
 * one, the caller's tail call sets its jump while the callee's activation is still live under it. {@code
 * CallOp.Active.resume} of that callee then sees a jump that is not its own and must leave it to the caller's
 * activation; taking it would run the caller's callee in the callee's frame, whose cells lack the caller's.
 *
 * <p>The expected result is reference jq 1.8.2's, {@code [5]}, under {@code ulimit -v 2000000} (#118's implementer,
 * 2026-11-15).
 */
class ForeignJumpTest {

  /**
   * {@code def g1: .+1; def g2: g1; …; def g513: g512;}, then f: a call chain past the inline cap (ADR-0040, Inlining's
   * callChain), so the call of g513 in f's body stays a call of a lifted function, at most one; f's own call is its tail
   * call, which reads f's value parameter from f's frame.
   */
  private static String filter() {
    StringBuilder f = new StringBuilder("def g1: .+1; ");
    for (int i = 2; i <= 513; i++) {
      f.append("def g").append(i).append(": g").append(i - 1).append("; ");
    }
    return f.append("def f($n): if . >= $n then . else g513 | f($n) end; [0|f(5)]").toString();
  }

  /**
   * The compile lowers 513 nested expansions, so it runs on a {@link TrampolineTest#BIG}-byte thread, as the call-chain
   * tests do; with {@code stackBytes} 0 the run is on the trampoline whatever the thread.
   */
  @Test
  void aCalleeLeavesItsCallersJumpToTheCaller() {
    String filter = filter();
    CallRuns.onStack(
        TrampolineTest.BIG,
        () -> {
          List<Line> lines = ExplainLines.lines(JqEngine.builder().compileMode(CompileMode.OPTIMISED).build(), filter);
          String f = ExplainLines.column(lines, "f/1");
          List<Line> calls = lines.stream().filter(l -> l.isNode() && l.fields().get(0).equals(f) && l.fields().get(2).equals("Call")).toList();
          assertThat(calls).as("premise: f's calls, the left side's and the tail call").extracting(l -> l.facts().get("call")).containsExactly("PLAIN", "TAIL_SELF");
        });
    Limits cli = Limits.cli();
    JqEngine zero =
        JqEngine.builder()
            .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), cli.recursionDepth(), Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), 0))
            .compileMode(CompileMode.OPTIMISED)
            .build();
    assertThat(TrampolineTest.ended(zero, filter, "null", TrampolineTest.BIG, false)).as("OPTIMISED run with stackBytes 0").isEqualTo("[[5]], ran out");
  }
}
