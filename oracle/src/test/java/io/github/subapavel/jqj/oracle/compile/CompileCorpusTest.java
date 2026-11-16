package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.oracle.StackProbe;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-mode oracle's modes (F-58, #109): {@code compile.txt} requires each id under the mode of its line, and a
 * case's run under each mode, {@code conservative} and {@code trampoline}, is judged against one optimised run.
 */
class CompileCorpusTest {

  /** jqj's F (CallRuns.F): reference jq 1.8.2 gives 9999 on 9999 (observed 2026-11-15, #109). */
  private static final String F = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; f";

  /** The thread on which F on 9999 completes only on the trampoline: DeepRuns' small stack. */
  private static final long SMALL = 256 * 1024;

  /** F-88: {@code max(4 × Limits.cli().stackBytes(), 2 MiB)}, with the command line's 512 KiB: 2 MiB. */
  private static final long RUNNER_STACK = 2L << 20;

  /**
   * F-88, #111: each case of the compile-mode oracle, a jqtest case through the API and a differential case through
   * the command line, runs its hook on one fixed thread, not the caller's, that holds the stack of a fresh
   * {@value #RUNNER_STACK}-byte thread.
   */
  @Test
  void everyCaseRunsOnTheRunnersOneThread(@TempDir Path scratch) {
    List<Thread> threads = new CopyOnWriteArrayList<>();
    List<Integer> frames = new CopyOnWriteArrayList<>();
    UnaryOperator<JqEngine.Builder> probe =
        b -> {
          threads.add(Thread.currentThread());
          frames.add(StackProbe.frames());
          return CompileModes.hook(CompileMode.CONSERVATIVE).apply(b);
        };
    List<CompileCorpus.Case> cases = CompileCorpus.cases(scratch);
    CompileCorpus.Case api = cases.stream().filter(c -> c.id().startsWith("jq.test#")).findFirst().orElseThrow();
    CompileCorpus.Case cli = cases.stream().filter(c -> c.id().equals("dot/null")).findFirst().orElseThrow();

    api.run().apply(probe);
    cli.run().apply(probe);

    assertThat(threads).as("the threads the hook ran on, one per case").hasSize(2);
    assertThat(threads.get(0)).as("the thread of the API case").isNotSameAs(Thread.currentThread()).isSameAs(threads.get(1));
    StackProbe.assertHolds(
        () -> {
          api.run().apply(probe);
          return frames.getLast();
        },
        RUNNER_STACK,
        "the compile-mode oracle's API case");
    StackProbe.assertHolds(
        () -> {
          cli.run().apply(probe);
          return frames.getLast();
        },
        RUNNER_STACK,
        "the compile-mode oracle's command-line case");
  }

  @Test
  void eachLineIsRequiredUnderItsMode(@TempDir Path dir) throws IOException {
    Path ratchet = dir.resolve("compile.txt");
    Files.writeString(ratchet, "# a header\n\na\td1\tconservative\na\td1\ttrampoline\nb\td2\tconservative\nc\td3\ttrampoline\n", UTF_8);

    Map<String, Set<String>> required = CompileCorpus.required(ratchet);

    assertThat(required).as("the ids of each mode").isEqualTo(Map.of("conservative", Set.of("a", "b"), "trampoline", Set.of("a", "c")));
    assertThat(CompileCorpus.modes("a", required, true)).as("the modes a is listed under").containsExactly("conservative", "trampoline");
    assertThat(CompileCorpus.modes("b", required, true)).as("the modes b is listed under").containsExactly("conservative");
    assertThat(CompileCorpus.modes("b", required, false)).as("the modes b is not listed under").containsExactly("trampoline");
    assertThat(CompileCorpus.modes("c", required, false)).as("the modes c is not listed under").containsExactly("conservative");
    assertThat(CompileCorpus.modes("d", required, true)).as("the modes an unlisted id is listed under").isEmpty();
    assertThat(CompileCorpus.modes("d", required, false)).as("the modes an unlisted id is not listed under").containsExactly("conservative", "trampoline");
    assertThat(CompileCorpus.required(dir.resolve("none.txt"))).as("the ids of a pass list that does not exist").isEmpty();
  }

  /**
   * One optimised run, then one run per mode through that mode's hook: F on 9999 on a 256 KiB thread, declaring 1 GiB,
   * ends with STACK in push form under the optimised and the conservative compile (the backstop witness of ADR-0046
   * "Where the stack is tested"), and completes only in the trampoline run, so only
   * that mode disagrees, and its verdict names it. Each run's end is its stdout here, so a termination is compared.
   */
  @Test
  void eachModeRunsThroughItsOwnHookAgainstOneOptimisedRun(@TempDir Path scratch) {
    DiffCase deep = new DiffCase("t/f-on-9999", List.of("-c", F), "9999\n".getBytes(UTF_8), Map.of(), Map.of(), null, DiffCase.JqjColumn.NONE, "d");
    AtomicInteger runs = new AtomicInteger();
    Limits l = CompileModes.limits();
    Limits gib = new Limits(l.steps(), l.deadline(), l.nestingDepth(), l.recursionDepth(), l.bytesBuilt(), l.bytesWritten(), l.regexMatchTime(), 1L << 30);
    CompileCorpus.Case c =
        new CompileCorpus.Case(
            deep.id(),
            deep.def(),
            hook -> {
              // S2b.5 (#111): a push run declaring the oracle's 512 KiB starts the trampoline at U_f, so the optimised
              // and the conservative runs, the first two, declare 1 GiB, which keeps every level on the Java stack; the
              // trampoline run, the third, keeps its stackBytes 0.
              UnaryOperator<JqEngine.Builder> declared = runs.incrementAndGet() < 3 ? b -> hook.apply(b).limits(gib) : hook;
              return endAsStdout(onSmallStack(() -> CompileModes.cli(deep, scratch, declared)));
            });

    Map<String, Verdict> verdicts = CompileCorpus.judge(c, CompileCorpus.MODES);

    assertThat(runs).as("the runs of one case under both modes").hasValue(3);
    assertThat(verdicts.keySet()).as("the modes judged, in order").containsExactly("conservative", "trampoline");
    assertThat(verdicts.get("conservative").kind()).as("the conservative run, which ends as the optimised one").isEqualTo(Verdict.Kind.PASS);
    assertThat(verdicts.get("trampoline").detail()).as("the trampoline run, which completes").isEqualTo("disagree: stdout differs at byte 0");
    assertThat(verdicts.get("trampoline").diff())
        .as("its comparison")
        .startsWith("t/f-on-9999: FAIL, the optimised and the trampoline run disagree\n")
        .contains("  stdout  optimised    STACK\n          trampoline   9999\\n\n");
  }

  /**
   * A mode outside {@link CompileCorpus#MODES} is refused, never run as another: a compile.txt line under it fails the
   * required check, naming the mode, and judging a case under it fails before any run.
   */
  @Test
  void aModeOutsideTheModesIsRefused(@TempDir Path dir) throws IOException {
    Path ratchet = dir.resolve("compile.txt");
    Files.writeString(ratchet, "a\td1\tconservative\na\td1\tcopy-only\n", UTF_8);
    Map<String, Set<String>> required = CompileCorpus.required(ratchet);
    AtomicInteger runs = new AtomicInteger();
    CompileCorpus.Case c =
        new CompileCorpus.Case(
            "a",
            "d1",
            hook -> {
              runs.incrementAndGet();
              return new Run.Ended(new Outcome(0, new byte[0], new byte[0]));
            });

    assertThatIllegalStateException()
        .as("the required check of a line under copy-only")
        .isThrownBy(() -> CompileCorpus.requireKnown(required, List.of(c)))
        .withMessage("compile oracle: required modes that are none of [conservative, trampoline]: [copy-only]");
    assertThatIllegalArgumentException()
        .as("judging a case under copy-only")
        .isThrownBy(() -> CompileCorpus.judge(c, List.of("copy-only")))
        .withMessage("compile oracle: no mode copy-only, only [conservative, trampoline]");
    assertThat(runs).as("the runs started for copy-only").hasValue(0);
  }

  /** A termination as an end with exit code 0 and its cause on stdout, so the runs are compared rather than left out. */
  private static Run endAsStdout(Run run) {
    if (run instanceof Run.Terminated(String cause)) return new Run.Ended(new Outcome(0, cause.getBytes(UTF_8), new byte[0]));
    return run;
  }

  /** What {@code run} gave on a new thread of {@link #SMALL} bytes. */
  private static Run onSmallStack(Supplier<Run> run) {
    AtomicReference<Run> outcome = new AtomicReference<>();
    Thread thread = new Thread(null, () -> outcome.set(run.get()), "small-stack", SMALL);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return outcome.get();
  }
}
