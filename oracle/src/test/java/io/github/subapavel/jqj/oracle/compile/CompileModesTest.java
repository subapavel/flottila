package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCase;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqNull;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-mode oracle's reach into jqj (ADR-0020 amendment for #60): the hook on the engine's builder, its limits,
 * and how a run through the command line or the public API ends.
 *
 * <p>The trampoline test (#109) assumes {@code static UnaryOperator<JqEngine.Builder> CompileModes.trampoline()}, the
 * hook of the third run (F-58).
 */
class CompileModesTest {

  @Test
  void theHookReachesThePackagePrivateSwitch() throws ReflectiveOperationException {
    for (CompileMode mode : CompileMode.values()) {
      JqEngine engine = CompileModes.hook(mode).apply(JqEngine.builder()).build();
      assertThat(CompileModes.modeOf(engine)).as("compile mode of an engine built through the hook for %s", mode).isEqualTo(mode);
    }
  }

  @Test
  void aReflectiveFailureIsAnIllegalStateException() {
    // A null mode makes the switch itself throw; the hook never falls back to a second optimised run.
    assertThatThrownBy(() -> CompileModes.hook(null).apply(JqEngine.builder()))
        .as("the hook for a null mode")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("compileMode");
    assertThatThrownBy(() -> CompileModes.method(JqEngine.Builder.class, "noSuchSwitch", CompileMode.class))
        .as("a switch that is not there")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("noSuchSwitch");
  }

  @Test
  void theHookUsesEmbeddedLimitsWithoutTheDeadline() {
    // ADR-0017's embedded limits, with neither the deadline nor the regex match time (ADR-0020 amendment for #60).
    assertThat(CompileModes.limits())
        .as("the compile-mode oracle's limits")
        .isEqualTo(new Limits(1_000_000_000L, Limits.UNLIMITED_TIME, 10_000, 4_194_304, 1L << 30, 1L << 30, Limits.UNLIMITED_TIME, 512 * 1024L));
  }

  /** jqj's F (CallRuns.F): reference jq 1.8.2 gives N on N for N up to 10^7 (F-87), 9999 on 9999 (observed 2026-11-15, #109). */
  private static final String F = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; f";

  /** The thread of a run that must hold 9 999 levels only on the driver: DeepRuns' small stack. */
  private static final long SMALL = 256 * 1024;

  /** What {@code run} gave on a new thread of {@link #SMALL} bytes; what else it threw is rethrown in an AssertionError. */
  private static Run onSmallStack(Supplier<Run> run) throws InterruptedException {
    AtomicReference<Object> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                outcome.set(run.get());
              } catch (Throwable t) {
                outcome.set(t);
              }
            },
            "small-stack",
            SMALL);
    thread.start();
    thread.join();
    if (outcome.get() instanceof Throwable t) throw new AssertionError("the run on a 256 KiB thread threw", t);
    return (Run) outcome.get();
  }

  /**
   * F-58, #109: the trampoline run is the conservative compile with {@code stackBytes} 0, so main and every body run on
   * the driver. F on 9999 through the command line then completes on a 256 KiB thread, where the conservative compile
   * with a declared stack of 1 GiB, which S2b.5's {@code U_f} keeps on the Java stack, ends with STACK.
   */
  @Test
  void theTrampolineHookIsConservativeWithStackBytesZero(@TempDir Path scratch) throws ReflectiveOperationException, InterruptedException {
    JqEngine engine = CompileModes.trampoline().apply(JqEngine.builder()).build();
    assertThat(CompileModes.modeOf(engine)).as("compile mode of an engine built through the trampoline hook").isEqualTo(CompileMode.CONSERVATIVE);
    // The oracle's limits, with neither the deadline nor the regex match time (F-58), and stackBytes 0.
    assertThat(CompileModes.trampolineLimits())
        .as("the trampoline run's limits")
        .isEqualTo(new Limits(1_000_000_000L, Limits.UNLIMITED_TIME, 10_000, 4_194_304, 1L << 30, 1L << 30, Limits.UNLIMITED_TIME, 0));

    DiffCase deep = new DiffCase("t/f-on-9999", List.of("-c", F), "9999\n".getBytes(UTF_8), Map.of(), Map.of(), null, DiffCase.JqjColumn.NONE, "d");
    assertEnded(onSmallStack(() -> CompileModes.cli(deep, scratch, CompileModes.trampoline())), "the trampoline run of F on 9999 on a 256 KiB thread", 0, "9999\n", "");

    Limits l = CompileModes.limits();
    Limits gib = new Limits(l.steps(), l.deadline(), l.nestingDepth(), l.recursionDepth(), l.bytesBuilt(), l.bytesWritten(), l.regexMatchTime(), 1L << 30);
    UnaryOperator<JqEngine.Builder> declared = b -> CompileModes.hook(CompileMode.CONSERVATIVE).apply(b).limits(gib);
    assertThat(onSmallStack(() -> CompileModes.cli(deep, scratch, declared))).as("the conservative run of F on 9999 with a declared stack of 1 GiB").isEqualTo(new Run.Terminated("STACK"));
  }

  /**
   * ADR-0047 #118: a case with {@code limit recursion-depth 16} runs every hook of this oracle under that budget, so a
   * tail call of 100 levels completes only in the optimised compile, which jumps, and the conservative and trampoline
   * runs end at the budget; a hook that is not this oracle's is refused. Witness: without the key the conservative run
   * completes under the oracle's budget.
   */
  @Test
  void aLimitedCaseRunsEveryHookUnderItsBudget(@TempDir Path scratch) {
    List<String> args = List.of("-c", "def f: if . >= 100 then . else .+1|f end; 0|f");
    DiffCase limited = new DiffCase("t/tail", args, "null\n".getBytes(UTF_8), Map.of(), Map.of(), null, DiffCase.JqjColumn.NONE, "d", 16L);
    DiffCase plain = new DiffCase("t/tail", args, "null\n".getBytes(UTF_8), Map.of(), Map.of(), null, DiffCase.JqjColumn.NONE, "d");

    assertEnded(CompileModes.cli(limited, scratch, CompileModes.hook(CompileMode.OPTIMISED)), "the optimised run under the key", 0, "100\n", "");
    assertThat(CompileModes.cli(limited, scratch, CompileModes.hook(CompileMode.CONSERVATIVE))).as("the conservative run under the key").isEqualTo(new Run.Terminated("BUDGET RECURSION_DEPTH"));
    assertThat(CompileModes.cli(limited, scratch, CompileModes.trampoline())).as("the trampoline run under the key").isEqualTo(new Run.Terminated("BUDGET RECURSION_DEPTH"));
    assertEnded(CompileModes.cli(plain, scratch, CompileModes.hook(CompileMode.CONSERVATIVE)), "witness: the conservative run without the key", 0, "100\n", "");
    UnaryOperator<JqEngine.Builder> foreign = b -> CompileModes.hook(CompileMode.CONSERVATIVE).apply(b);
    assertThatThrownBy(() -> CompileModes.cli(limited, scratch, foreign))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("case t/tail has limit recursion-depth, which only a hook of CompileModes applies");
  }

  /** The hook of {@code mode} with no steps: the read's one tick for {@code 1} ends the run (StepBudgetTest). */
  private static UnaryOperator<JqEngine.Builder> noSteps(CompileMode mode) {
    Limits l = CompileModes.limits();
    Limits none = new Limits(0, l.deadline(), l.nestingDepth(), l.recursionDepth(), l.bytesBuilt(), l.bytesWritten(), l.regexMatchTime(), l.stackBytes());
    return b -> CompileModes.hook(mode).apply(b).limits(none);
  }

  private static DiffCase compactDot(String stdin) {
    return new DiffCase("t/compact-dot", List.of("-c", "."), stdin.getBytes(UTF_8), Map.of(), Map.of(), null, DiffCase.JqjColumn.NONE, "d");
  }

  private static JqTestCase dot(String input) {
    return new JqTestCase("t.test#000000000000", 1, JqTestCase.Mode.RESULTS, ".", input, List.of(input));
  }

  @Test
  void aStepTerminationThroughTheCommandLineIsLeftOut(@TempDir Path scratch) {
    Run optimised = CompileModes.cli(compactDot("1\n"), scratch, noSteps(CompileMode.OPTIMISED));
    Run conservative = CompileModes.cli(compactDot("1\n"), scratch, noSteps(CompileMode.CONSERVATIVE));

    assertThat(conservative).as("the conservative run with no steps").isEqualTo(new Run.Terminated("BUDGET STEPS"));
    Verdict v = CompileModeRunner.judge("t/compact-dot", optimised, conservative);
    assertThat(v.detail()).as("verdict of a step termination in both modes").isEqualTo("left out: the optimised run ended with BUDGET STEPS");
  }

  @Test
  void aStepTerminationThroughTheApiIsLeftOut() {
    Run optimised = CompileModes.api(dot("1"), noSteps(CompileMode.OPTIMISED));
    Run conservative = CompileModes.api(dot("1"), noSteps(CompileMode.CONSERVATIVE));

    assertThat(optimised).as("the optimised run with no steps").isEqualTo(new Run.Terminated("BUDGET STEPS"));
    assertThat(CompileModeRunner.judge("t.test#000000000000", optimised, conservative).detail())
        .as("verdict of a step termination in both modes")
        .startsWith("left out: ");
  }

  @Test
  void aCaseThroughTheCommandLineEndsWithItsExitCodeAndStreams(@TempDir Path scratch) {
    // jq-ref 1.8.2, observed 2026-11-12: printf '{"a":[1,2]}' | jq-ref -c . -> rc 0, stdout {"a":[1,2]} and LF.
    Run run = CompileModes.cli(compactDot("{\"a\":[1,2]}"), scratch, CompileModes.hook(CompileMode.CONSERVATIVE));

    assertEnded(run, "the conservative run", 0, "{\"a\":[1,2]}\n", "");
  }

  @Test
  void aCaseThroughTheApiEndsWithItsResultsOneLineEach() {
    // jq-ref 1.8.2, observed 2026-11-12: printf '{"a":[1,2]}' | jq-ref -c '.a' -> [1,2] and LF.
    JqTestCase field = new JqTestCase("t.test#000000000001", 1, JqTestCase.Mode.RESULTS, ".a", "{\"a\":[1,2]}", List.of("[1,2]"));

    Run run = CompileModes.api(field, CompileModes.hook(CompileMode.CONSERVATIVE));

    assertEnded(run, "the conservative run of .a", 0, "[1,2]\n", "");
  }

  @Test
  void aFilterErrorThroughTheApiEndsWithItsValue() {
    // jq-ref 1.8.2, observed 2026-11-12: printf '1' | jq-ref .a -> rc 5,
    //   "jq: error (at <stdin>:0): Cannot index number with string ("a")".
    JqTestCase field = new JqTestCase("t.test#000000000004", 1, JqTestCase.Mode.RESULTS, ".a", "1", List.of());

    assertEnded(CompileModes.api(field, CompileModes.hook(CompileMode.CONSERVATIVE)), "the run of .a on 1", 5, "", "\"Cannot index number with string (\\\"a\\\")\"");
  }

  @Test
  void anInvalidInputThroughTheApiEndsWithTheReadersMessage() {
    // jq-ref 1.8.2, observed 2026-11-12: printf 'nul' | jq-ref . -> rc 5, "jq: parse error: Invalid literal at EOF at
    //   line 1, column 3". The API's run gives the reader's message.
    Run run = CompileModes.api(dot("nul"), CompileModes.hook(CompileMode.CONSERVATIVE));

    assertThat(run).as("the run on nul").isInstanceOf(Run.Ended.class);
    assertThat(((Run.Ended) run).outcome().rc()).as("its exit code").isEqualTo(5);
    assertThat(((Run.Ended) run).outcome().stdout()).as("its stdout").isEmpty();
    assertThat(new String(((Run.Ended) run).outcome().stderr(), UTF_8)).as("its stderr").contains("line 1");
  }

  @Test
  void anExceptionThatIsNotJqjsIsThrown(@TempDir Path scratch) {
    UnaryOperator<JqEngine.Builder> broken =
        b -> {
          throw new IllegalArgumentException("boom");
        };

    assertThat(CompileModes.api(dot("1"), broken)).as("the API run with a hook that throws").isInstanceOf(Run.Threw.class);
    assertThat(CompileModes.cli(compactDot("1"), scratch, broken)).as("the command-line run with a hook that throws").isInstanceOf(Run.Threw.class);
  }

  @Test
  void aFailCaseThroughTheApiOnlyCompiles() {
    JqTestCase undefined = new JqTestCase("t.test#000000000002", 1, JqTestCase.Mode.FAIL, "$x", null, List.of("jq: error: $x is not defined"));
    JqTestCase compiles = new JqTestCase("t.test#000000000003", 1, JqTestCase.Mode.FAIL_IGNORE_MSG, ".", null, List.of());

    // jq-ref 1.8.2, observed 2026-11-12: jq-ref -n '$x' -> rc 3, first line
    //   "jq: error: $x is not defined at <top-level>, line 1, column 1:". jqj's text is the compile exception's message.
    Run failed = CompileModes.api(undefined, CompileModes.hook(CompileMode.OPTIMISED));
    assertThat(failed).as("the run of a program that does not compile").isInstanceOf(Run.Ended.class);
    assertThat(((Run.Ended) failed).outcome().rc()).as("its exit code").isEqualTo(3);
    assertThat(((Run.Ended) failed).outcome().stdout()).as("its stdout").isEmpty();
    assertThat(((Run.Ended) failed).outcome().stderr()).as("its stderr").isNotEmpty();
    assertEnded(CompileModes.api(compiles, CompileModes.hook(CompileMode.OPTIMISED)), "the run of a FAIL case that compiles", 0, "", "");
  }

  @Test
  void aHaltIsCompared() {
    // halt and halt_error are deterministic: they end the run like any result, so the modes are compared.
    JqTermination halt = new JqTermination(JqTermination.Cause.HALT, 1, JqNull.NULL, null);
    Run halted = CompileModes.termination(halt, () -> new Outcome(halt.exitCode(), new byte[0], "x".getBytes(UTF_8)));
    Run other = CompileModes.termination(halt, () -> new Outcome(halt.exitCode(), new byte[0], "y".getBytes(UTF_8)));

    assertEnded(halted, "a halt", 1, "", "x");
    assertThat(CompileModeRunner.judge("t/halt", halted, halted).kind()).as("two equal halts").isEqualTo(Verdict.Kind.PASS);
    assertThat(CompileModeRunner.judge("t/halt", halted, other).detail()).as("two differing halts").startsWith("disagree: ");
  }

  @Test
  void everyOtherTerminationIsLeftOut() {
    assertThat(CompileModes.termination(new JqTermination(JqTermination.Cause.STACK, 5, JqNull.NULL, null), () -> null))
        .as("a stack termination")
        .isEqualTo(new Run.Terminated("STACK"));
    assertThat(CompileModes.termination(new JqTermination(JqTermination.Cause.STALE_VIEW, 5, JqNull.NULL, null), () -> null))
        .as("a stale-view termination")
        .isEqualTo(new Run.Terminated("STALE_VIEW"));
  }

  /** Run.Ended's Outcome compares its byte arrays by identity, so the parts are compared one by one. */
  private static void assertEnded(Run run, String what, int rc, String stdout, String stderr) {
    assertThat(run).as(what).isInstanceOf(Run.Ended.class);
    Outcome o = ((Run.Ended) run).outcome();
    assertThat(o.rc()).as("%s: exit code", what).isEqualTo(rc);
    assertThat(new String(o.stdout(), UTF_8)).as("%s: stdout", what).isEqualTo(stdout);
    assertThat(new String(o.stderr(), UTF_8)).as("%s: stderr", what).isEqualTo(stderr);
  }
}
