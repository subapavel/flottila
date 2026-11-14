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
