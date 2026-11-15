package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A chain of jumps leaves its activation exactly once (ADR-0047 #118, "Where a jump is taken"; ADR-0046): at {@code U_f}
 * 1 every call from main starts the trampoline, so f's and g's jumps run inside a starter's body and are taken by
 * {@code CallOp}'s entry loop, f's in the push form and g's in the value form, and the jumps of q's inner call of f are
 * taken by {@code CallOp.Active} on the driver. After them a recursion of p that needs exactly the recursion-depth
 * budget of live activations completes, and one level more ends with the budget: an activation left twice would let
 * the deeper one run, and one never left would end the shallower one.
 *
 * <p>Expected results are reference jq 1.8.2's: 15 and 16 for the filter with p's depth 15 and 16, under {@code ulimit
 * -v 2000000} (#118's implementer, 2026-11-15).
 */
class JumpLeavesOnceTest {
  /** The recursion-depth budget: p from 0 to {@code depth} holds {@code depth + 1} live activations. */
  private static final int BUDGET = 16;

  /** The jumping calls, then p from 0 to {@code depth}, whose call of itself has {@code + 0} to run after it. */
  private static String filter(int depth) {
    return "def f: if . >= 100 then ., . else .+1|f end; def g: if . >= 100 then . else .+1|g end;"
        + " def q: if . >= 5 then (0|f) else (.+1|q)+0 end; def p: if . >= "
        + depth
        + " then . else (.+1|p)+0 end; (0|f|empty), (0|g|empty), (0|q|empty), (0|p)";
  }

  /** The filter at {@code U_f} 1 under the recursion-depth budget {@link #BUDGET}, on a thread of four times its stack. */
  private static String ended(String filter) {
    long stackBytes = StackUnitsTest.constants(filter, CompileMode.OPTIMISED).stackBytesFor(1);
    Limits cli = Limits.cli();
    JqEngine engine =
        JqEngine.builder()
            .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), BUDGET, Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), stackBytes))
            .compileMode(CompileMode.OPTIMISED)
            .build();
    return StackUnitsTest.ended(engine, filter, "null", StackUnitsTest.thread(stackBytes));
  }

  /** The kind and form of the one Call of {@code body} whose callee is {@code callee}. */
  private static List<String> call(List<Line> lines, String body, String callee) {
    String column = ExplainLines.column(lines, body);
    String target = ExplainLines.column(lines, callee);
    List<Line> found = lines.stream().filter(l -> l.isNode() && l.fields().get(0).equals(column) && l.fields().get(2).equals("Call") && target.equals(l.facts().get("callee"))).toList();
    assertThat(found).as("the Calls of %s in %s", callee, body).hasSize(1);
    return List.of(found.getFirst().facts().get("call"), found.getFirst().facts().get("form"));
  }

  @Test
  void aJumpingActivationIsLeftOnce() {
    List<Line> lines = ExplainLines.lines(JqEngine.builder().compileMode(CompileMode.OPTIMISED).build(), filter(BUDGET - 1));
    assertThat(call(lines, "f/0", "f/0")).as("premise: f's jump, in push form").containsExactly("TAIL_SELF", "push");
    assertThat(call(lines, "g/0", "g/0")).as("premise: g's jump, in value form").containsExactly("TAIL_SELF", "value");
    assertThat(call(lines, "q/0", "f/0")).as("premise: q's call of f").containsExactly("PLAIN", "push");
    assertThat(call(lines, "p/0", "p/0")).as("premise: p's call of itself, no jump").first().isEqualTo("RECURSIVE");

    assertThat(ended(filter(BUDGET - 1))).as("p to %d after the jumps, %d live activations", BUDGET - 1, BUDGET).isEqualTo("[15], ran out");
    assertThat(ended(filter(BUDGET))).as("p to %d after the jumps, one over the budget", BUDGET).isEqualTo("[], termination BUDGET RECURSION_DEPTH");
  }
}
