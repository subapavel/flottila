package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The compile-mode oracle's verdict on one case's two runs (ADR-0020 amendment for #60): planted pairs of runs, as
 * the optimised and the conservative compile could end. The rules, in order: an exception out of a run is CRASH, a
 * Stub on either side is FAIL {@code stub:}, a termination on either side is FAIL {@code left out:}, equal exit
 * code, stdout and stderr is PASS, and anything else is a Disagreement, FAIL {@code disagree:}.
 *
 * <p>The trampoline tests (#109) assume {@code CompileModeRunner.judge(String id, Run optimised, String mode, Run
 * other)}, which judges {@code other}, the run of {@code mode}, against the optimised run and names it {@code mode}.
 */
class CompileModeRunnerTest {

  private static Run ended(int rc, String stdout, String stderr) {
    return new Run.Ended(new Outcome(rc, stdout.getBytes(UTF_8), stderr.getBytes(UTF_8)));
  }

  @Test
  void equalRunsPass() {
    Verdict v = CompileModeRunner.judge("dot/x", ended(0, "1\n", ""), ended(0, "1\n", ""));

    assertThat(v.kind()).as("verdict of two equal runs").isEqualTo(Verdict.Kind.PASS);
    assertThat(v.detail()).as("detail of a pass").isEmpty();
    assertThat(v.diff()).as("diff of a pass").isEmpty();
  }

  @Test
  void aPlantedDisagreementInStdoutFails() {
    Verdict v = CompileModeRunner.judge("dot/x", ended(0, "[1,2]\n", ""), ended(0, "[1,3]\n", ""));

    assertThat(v.kind()).as("verdict of a stdout disagreement").isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).as("detail of a stdout disagreement").isEqualTo("disagree: stdout differs at byte 3");
    assertThat(v.diff())
        .as("diff of a stdout disagreement")
        .isEqualTo(
            "dot/x: FAIL, the optimised and the conservative compile disagree\n"
                + "  rc      same\n"
                + "  stdout  optimised    [1,2]\\n\n"
                + "          conservative [1,3]\\n\n"
                + "          first difference at byte 3\n"
                + "  stderr  same\n");
  }

  @Test
  void aPlantedDisagreementInExitCodeAndStderrFails() {
    Verdict v = CompileModeRunner.judge("jq.test#abc", ended(0, "", ""), ended(5, "", "jq: error (at <stdin>:0): x\n"));

    assertThat(v.kind()).as("verdict of an rc and stderr disagreement").isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail())
        .as("detail of an rc and stderr disagreement")
        .isEqualTo("disagree: rc 0 optimised, 5 conservative; stderr differs at byte 0");
    assertThat(v.diff())
        .as("diff of an rc and stderr disagreement")
        .isEqualTo(
            "jq.test#abc: FAIL, the optimised and the conservative compile disagree\n"
                + "  rc      optimised    0\n"
                + "          conservative 5\n"
                + "  stdout  same\n"
                + "  stderr  optimised    (empty)\n"
                + "          conservative jq: error (at <stdin>:0): x\\n\n"
                + "          first difference at byte 0\n");
  }

  @Test
  void aLongDifferenceShowsAWindowAroundIt() {
    String common = "a".repeat(300);
    Verdict v = CompileModeRunner.judge("gen/x/1", ended(0, common + "b" + common, ""), ended(0, common + "c" + common, ""));

    // 40 bytes before the difference at 300, 200 bytes from there, of 601.
    String window = "a".repeat(40);
    assertThat(v.diff())
        .as("diff of a difference inside long streams")
        .contains("  stdout  optimised    ..." + window + "b" + "a".repeat(159) + "... (601 bytes)\n")
        .contains("          conservative ..." + window + "c" + "a".repeat(159) + "... (601 bytes)\n")
        .contains("          first difference at byte 300\n");
  }

  @Test
  void aTerminationInEitherModeIsLeftOut() {
    Verdict conservative = CompileModeRunner.judge("dot/x", ended(0, "1\n", ""), new Run.Terminated("BUDGET STEPS"));
    Verdict optimised = CompileModeRunner.judge("dot/x", new Run.Terminated("STACK"), ended(0, "1\n", ""));
    Verdict both = CompileModeRunner.judge("dot/x", new Run.Terminated("CANCELLED"), new Run.Terminated("CANCELLED"));

    assertThat(conservative.kind()).as("verdict of a conservative termination").isEqualTo(Verdict.Kind.FAIL);
    assertThat(conservative.detail()).as("detail of a conservative termination").isEqualTo("left out: the conservative run ended with BUDGET STEPS");
    assertThat(optimised.detail()).as("detail of an optimised termination").isEqualTo("left out: the optimised run ended with STACK");
    assertThat(both.kind()).as("verdict of two terminations").isEqualTo(Verdict.Kind.FAIL);
    assertThat(both.detail()).as("detail of two terminations").isEqualTo("left out: the optimised run ended with CANCELLED");
  }

  @Test
  void aStubOnEitherSideFails() {
    Verdict stderr = CompileModeRunner.judge("dot/x", ended(0, "", ""), ended(3, "", "jqj stub: the builtin f/0 is not built yet\n"));
    Verdict thrown = CompileModeRunner.judge("dot/x", new Run.Threw(new IllegalStateException("jqj stub: g is not built yet")), ended(0, "", ""));

    assertThat(stderr.kind()).as("verdict of a Stub in stderr").isEqualTo(Verdict.Kind.FAIL);
    assertThat(stderr.detail()).as("detail of a Stub in stderr").isEqualTo("stub: the conservative run reached a Stub: jqj stub: the builtin f/0 is not built yet\\n");
    assertThat(thrown.kind()).as("verdict of a Stub thrown").isEqualTo(Verdict.Kind.FAIL);
    assertThat(thrown.detail())
        .as("detail of a Stub thrown")
        .isEqualTo("stub: the optimised run reached a Stub: java.lang.IllegalStateException: jqj stub: g is not built yet");
  }

  @Test
  void anExceptionOutOfARunIsCrash() {
    IllegalStateException boom = new IllegalStateException("boom");
    Verdict v = CompileModeRunner.judge("dot/x", ended(0, "", ""), new Run.Threw(boom));

    assertThat(v.kind()).as("verdict of an exception").isEqualTo(Verdict.Kind.CRASH);
    assertThat(v.detail()).as("detail of an exception").isEqualTo("the conservative run threw java.lang.IllegalStateException: boom");
    assertThat(v.diff()).as("diff of an exception").startsWith("dot/x: CRASH, the conservative run threw java.lang.IllegalStateException: boom");
  }

  @Test
  void aCrashComesBeforeAStubAndAStubBeforeATermination() {
    Run crash = new Run.Threw(new IllegalStateException("boom"));
    Run stub = ended(3, "", "jqj stub: f\n");
    Run terminated = new Run.Terminated("BUDGET STEPS");

    assertThat(CompileModeRunner.judge("dot/x", stub, crash).kind()).as("a Stub against a crash").isEqualTo(Verdict.Kind.CRASH);
    assertThat(CompileModeRunner.judge("dot/x", terminated, stub).detail()).as("a termination against a Stub").startsWith("stub: ");
    assertThat(CompileModeRunner.judge("dot/x", terminated, ended(0, "1\n", "")).detail()).as("a termination against a disagreeing end").startsWith("left out: ");
  }

  /**
   * F-58, #109: the third run, mode {@code trampoline} (the conservative compile with {@code stackBytes} 0), is judged
   * against the optimised run by the same rules, and every verdict names it where it names a run.
   */
  @Test
  void theTrampolineRunIsJudgedAgainstTheOptimisedRun() {
    Verdict same = CompileModeRunner.judge("heap/x", ended(0, "[1]\n", ""), "trampoline", ended(0, "[1]\n", ""));
    Verdict rc = CompileModeRunner.judge("heap/x", ended(0, "", ""), "trampoline", ended(5, "", "jq: error (at <stdin>:0): x\n"));
    Verdict stub = CompileModeRunner.judge("heap/x", ended(0, "", ""), "trampoline", ended(3, "", "jqj stub: f\n"));
    Verdict crash = CompileModeRunner.judge("heap/x", ended(0, "", ""), "trampoline", new Run.Threw(new IllegalStateException("boom")));

    assertThat(same.kind()).as("verdict of a trampoline run equal to the optimised one").isEqualTo(Verdict.Kind.PASS);
    assertThat(same.detail()).as("detail of that pass").isEmpty();
    assertThat(same.diff()).as("diff of that pass").isEmpty();
    assertThat(rc.kind()).as("verdict of a trampoline run that disagrees").isEqualTo(Verdict.Kind.FAIL);
    assertThat(rc.detail()).as("detail of an rc and stderr disagreement").isEqualTo("disagree: rc 0 optimised, 5 trampoline; stderr differs at byte 0");
    assertThat(stub.detail()).as("detail of a Stub in the trampoline run").isEqualTo("stub: the trampoline run reached a Stub: jqj stub: f\\n");
    assertThat(crash.kind()).as("verdict of an exception out of the trampoline run").isEqualTo(Verdict.Kind.CRASH);
    assertThat(crash.detail()).as("detail of that exception").isEqualTo("the trampoline run threw java.lang.IllegalStateException: boom");
  }

  /** F-58: a case whose trampoline run ends in a termination is left out, as one whose optimised run does. */
  @Test
  void aTerminationUnderTheTrampolineIsLeftOut() {
    Verdict stack = CompileModeRunner.judge("heap/x", ended(0, "1\n", ""), "trampoline", new Run.Terminated("STACK"));
    Verdict depth = CompileModeRunner.judge("heap/x", ended(0, "1\n", ""), "trampoline", new Run.Terminated("BUDGET RECURSION_DEPTH"));
    Verdict optimised = CompileModeRunner.judge("heap/x", new Run.Terminated("BUDGET STEPS"), "trampoline", ended(0, "1\n", ""));

    assertThat(stack.kind()).as("verdict of a trampoline run that exhausted the stack").isEqualTo(Verdict.Kind.FAIL);
    assertThat(stack.detail()).as("detail of that termination").isEqualTo("left out: the trampoline run ended with STACK");
    assertThat(depth.detail()).as("detail of a recursion-depth termination").isEqualTo("left out: the trampoline run ended with BUDGET RECURSION_DEPTH");
    assertThat(optimised.detail()).as("detail of an optimised termination against a trampoline run").isEqualTo("left out: the optimised run ended with BUDGET STEPS");
  }

  /**
   * ADR-0047 #118: under {@code limit recursion-depth} the other run, which stores no tail kind, passes only as a
   * termination over the recursion-depth budget, while the optimised run ended; any other end of either fails.
   * Witness: without the key the same depth termination is left out.
   */
  @Test
  void aLimitedCasePassesOnlyWhenTheOtherRunEndsAtTheDepthBudget() {
    Run depth = new Run.Terminated("BUDGET RECURSION_DEPTH");
    Verdict pass = CompileModeRunner.judge("tail/x", ended(0, "1\n", ""), "conservative", depth, true);
    Verdict ended = CompileModeRunner.judge("tail/x", ended(0, "1\n", ""), "trampoline", ended(0, "1\n", ""), true);
    Verdict steps = CompileModeRunner.judge("tail/x", ended(0, "1\n", ""), "conservative", new Run.Terminated("BUDGET STEPS"), true);
    Verdict optimised = CompileModeRunner.judge("tail/x", depth, "conservative", depth, true);
    Verdict stub = CompileModeRunner.judge("tail/x", ended(0, "", ""), "conservative", ended(3, "", "jqj stub: f\n"), true);

    assertThat(pass.kind()).as("verdict of a depth end under the key").isEqualTo(Verdict.Kind.PASS);
    assertThat(pass.detail()).as("detail of that pass").isEmpty();
    assertThat(ended.detail()).as("detail of a run that ended under the key").isEqualTo("no depth end: under limit recursion-depth the trampoline run must end with BUDGET RECURSION_DEPTH; it ended with rc 0");
    assertThat(steps.detail()).as("detail of another termination under the key").isEqualTo("no depth end: under limit recursion-depth the conservative run must end with BUDGET RECURSION_DEPTH; it ended with BUDGET STEPS");
    assertThat(optimised.detail()).as("detail of an optimised termination under the key").isEqualTo("left out: the optimised run ended with BUDGET RECURSION_DEPTH");
    assertThat(stub.detail()).as("a Stub comes first under the key too").startsWith("stub: ");
    assertThat(CompileModeRunner.judge("tail/x", ended(0, "1\n", ""), "conservative", depth, false).detail())
        .as("witness: the same end without the key")
        .isEqualTo("left out: the conservative run ended with BUDGET RECURSION_DEPTH");
  }

  /** The readable comparison of a trampoline Disagreement names the trampoline run, aligned as the optimised one. */
  @Test
  void aTrampolineDisagreementNamesTheTrampolineRun() {
    Verdict stdout = CompileModeRunner.judge("heap/x", ended(0, "[1,2]\n", ""), "trampoline", ended(0, "[1,3]\n", ""));
    Verdict rc = CompileModeRunner.judge("heap/y", ended(0, "", ""), "trampoline", ended(5, "", "jq: error (at <stdin>:0): x\n"));

    assertThat(stdout.detail()).as("detail of a stdout disagreement").isEqualTo("disagree: stdout differs at byte 3");
    assertThat(stdout.diff())
        .as("diff of a stdout disagreement")
        .isEqualTo(
            "heap/x: FAIL, the optimised and the trampoline run disagree\n"
                + "  rc      same\n"
                + "  stdout  optimised    [1,2]\\n\n"
                + "          trampoline   [1,3]\\n\n"
                + "          first difference at byte 3\n"
                + "  stderr  same\n");
    assertThat(rc.diff())
        .as("diff of an rc and stderr disagreement")
        .isEqualTo(
            "heap/y: FAIL, the optimised and the trampoline run disagree\n"
                + "  rc      optimised    0\n"
                + "          trampoline   5\n"
                + "  stdout  same\n"
                + "  stderr  optimised    (empty)\n"
                + "          trampoline   jq: error (at <stdin>:0): x\\n\n"
                + "          first difference at byte 0\n");
  }

  /** The planted pairs: the verdict kind must not depend on which mode ended which way. */
  static Stream<Arguments> plantedPairs() {
    return Stream.of(
        Arguments.argumentSet("equal", ended(0, "1\n", ""), ended(0, "1\n", "")),
        Arguments.argumentSet("disagreement", ended(0, "1\n", ""), ended(0, "2\n", "")),
        Arguments.argumentSet("termination", ended(0, "1\n", ""), new Run.Terminated("BUDGET STEPS")),
        Arguments.argumentSet("stub in stderr", ended(0, "", ""), ended(3, "", "jqj stub: f\n")),
        Arguments.argumentSet("stub thrown", ended(0, "", ""), new Run.Threw(new IllegalStateException("jqj stub: g"))),
        Arguments.argumentSet("crash", ended(0, "", ""), new Run.Threw(new IllegalStateException("boom"))),
        Arguments.argumentSet("crash against a stub", ended(3, "", "jqj stub: f\n"), new Run.Threw(new IllegalStateException("boom"))),
        Arguments.argumentSet("stub against a termination", new Run.Terminated("STACK"), ended(3, "", "jqj stub: f\n")));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("plantedPairs")
  void swappingTheModesKeepsTheVerdictKind(Run a, Run b) {
    Verdict forward = CompileModeRunner.judge("dot/x", a, b);
    Verdict swapped = CompileModeRunner.judge("dot/x", b, a);

    assertThat(kind(swapped)).as("verdict kind with the modes swapped").isEqualTo(kind(forward));
  }

  /** The verdict and, for a FAIL, its detail's prefix ({@code stub}, {@code left out}, {@code disagree}). */
  private static String kind(Verdict v) {
    return v.kind() == Verdict.Kind.FAIL ? "FAIL " + v.detail().substring(0, v.detail().indexOf(':')) : v.kind().name();
  }
}
