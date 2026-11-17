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
 * activation; taking it would run the caller's callee in the callee's frame, whose cells lack the caller's. On the stack
 * a left call that may break out runs in push form, and {@code CallOp.run}'s entry loop makes the same check (#118
 * ruling J4).
 *
 * <p>The expected result is reference jq 1.8.2's, {@code [5]}, under {@code ulimit -v 2000000} (#118's implementer,
 * 2026-11-15).
 */
class ForeignJumpTest {

  /**
   * #118 ruling J4: g recurses and may break out of main's label, so f's left call of g stays a call, and though it is at
   * most one it runs in push form (ADR-0008, ruling J3): g emits its result inside its body, and f's tail call sets its
   * jump there, while g's activation is live under it.
   */
  private static final String BREAKING =
      "[label $out | def g: if . == -1 then break $out elif . > 100 then (. - 100 | g) else .+1 end; "
          + "def f($n): if . >= $n then . else g | f($n) end; 0 | f(5)]";

  /** Enough for the run of {@link #BREAKING}; a callee that takes its caller's jump may never end, so the run has a limit. */
  private static final long STEPS = 1_000_000;

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

  /**
   * Pins the frame check of {@code CallOp.run}'s entry loop, the push form's (CallOp.java:64 at a3a0856): a left call
   * that may break out is push form even when it is at most one (J3), so on the default stack, with no trampoline, g's
   * {@code CallOp.run} returns from its body with f's jump pending and must return, leaving the jump to f's activation;
   * taking it would run f's body in g's frame. The two premises are read off the explain text; the expected result is
   * {@code tail/breaking-left-call-leaves-the-callers-jump} of diff/tail.rec, reference jq 1.8.2's {@code [5]}, also run
   * under {@code ulimit -v 2000000} (2026-11-15).
   */
  @Test
  void aPushFormCalleeLeavesItsCallersJumpToTheCaller() {
    Limits cli = Limits.cli();
    JqEngine engine =
        JqEngine.builder()
            .limits(new Limits(STEPS, Limits.UNLIMITED_TIME, cli.nestingDepth(), cli.recursionDepth(), Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), cli.stackBytes()))
            .compileMode(CompileMode.OPTIMISED)
            .build();
    List<Line> lines = ExplainLines.lines(engine, BREAKING);
    String f = ExplainLines.column(lines, "f/1");
    String g = ExplainLines.column(lines, "g/0");
    Line left = call(lines, f, g);
    assertThat(left.facts())
        .as("premise: f's left call of g")
        .containsEntry("call", "PLAIN")
        .containsEntry("card", "AT_MOST_ONE")
        .containsEntry("breaks", "true")
        .containsEntry("form", "push");
    assertThat(call(lines, f, f).facts()).as("premise: f's own call").containsEntry("call", "TAIL_SELF");
    assertThat(TrampolineTest.ended(engine, BREAKING, "null", CallRuns.STACK, false)).as("OPTIMISED run on the default stack").isEqualTo("[[5]], ran out");
  }

  /** The one Call line in the column {@code column} whose callee is the function of the column {@code callee}. */
  private static Line call(List<Line> lines, String column, String callee) {
    List<Line> found =
        lines.stream().filter(l -> l.isNode() && l.fields().get(0).equals(column) && l.fields().get(2).equals("Call") && callee.equals(l.facts().get("callee"))).toList();
    assertThat(found).as("the call of %s in %s, in %s", callee, column, lines).hasSize(1);
    return found.getFirst();
  }
}
