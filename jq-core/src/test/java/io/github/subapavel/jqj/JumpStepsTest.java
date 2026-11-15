package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A jump ticks one step in each of the forms it runs in (ADR-0047 #118, ADR-0008): TailCallKindsTest's
 * {@code jumpTicksOneStep} pins the value form; here the push form, whose body gives each result twice, on the Java
 * stack, and the same filter with {@code stackBytes} 0, where every jump starts on the driver. In each, 1000 levels more
 * cost 1000 steps more, and every level's only step is its jump. The recursion-depth budget of 16 makes the runs
 * complete only by jumping.
 *
 * <p>Expected results are reference jq 1.8.2's: {@code [1000,1000]} and {@code [2000,2000]}, under {@code ulimit -v
 * 2000000} (#118's implementer, 2026-11-15).
 */
class JumpStepsTest {
  private static final Pattern STEPS = Pattern.compile(", (\\d+) steps$");

  /** f's call of itself is a push-form jump: its body is many-valued. */
  private static String filter(int n) {
    return "def f: if . >= " + n + " then ., . else .+1|f end; 0|f";
  }

  /** Limits.cli() with no step budget, a recursion-depth budget of 16 and the declared stack {@code stackBytes}. */
  private static JqEngine engine(long stackBytes) {
    Limits cli = Limits.cli();
    return JqEngine.builder()
        .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), 16, Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), stackBytes))
        .compileMode(CompileMode.OPTIMISED)
        .build();
  }

  /** The steps of the runs of 1000 and of 2000 levels under {@code engine}, each checked against reference jq. */
  private static long[] steps(JqEngine engine, String where) {
    int[] levels = {1000, 2000};
    long[] steps = new long[2];
    for (int i = 0; i < 2; i++) {
      String ended = TrampolineTest.ended(engine, filter(levels[i]), "null", SpecialiseExplainTest.RUNNER_STACK, true);
      assertThat(ended).as("OPTIMISED run of %s %s", filter(levels[i]), where).startsWith("[" + levels[i] + ", " + levels[i] + "], ran out, ");
      Matcher m = STEPS.matcher(ended);
      assertThat(m.find()).as("the steps of %s", ended).isTrue();
      steps[i] = Long.parseLong(m.group(1));
    }
    return steps;
  }

  @Test
  void aPushFormJumpTicksOneStep() {
    List<Line> lines = ExplainLines.lines(engine(Limits.cli().stackBytes()), filter(1000));
    String f = ExplainLines.column(lines, "f/0");
    List<Line> calls = lines.stream().filter(l -> l.isNode() && l.fields().get(0).equals(f) && l.fields().get(2).equals("Call")).toList();
    assertThat(calls).as("premise: f's one call").hasSize(1);
    assertThat(calls.getFirst().facts()).as("premise: f's call").containsEntry("call", "TAIL_SELF").containsEntry("form", "push");

    long[] steps = steps(engine(Limits.cli().stackBytes()), "on the Java stack");
    assertThat(steps[1] - steps[0]).as("the steps of 1000 push-form jumps more").isEqualTo(1000);
  }

  @Test
  void aJumpOnTheDriverTicksOneStep() {
    long[] steps = steps(engine(0), "with stackBytes 0");
    assertThat(steps[1] - steps[0]).as("the steps of 1000 jumps more on the driver").isEqualTo(1000);
  }
}
