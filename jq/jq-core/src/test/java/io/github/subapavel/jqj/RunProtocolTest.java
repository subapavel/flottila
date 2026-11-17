package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.assertCancelled;
import static io.github.subapavel.jqj.RunBudgets.bytesWritten;
import static io.github.subapavel.jqj.RunBudgets.deadline;
import static io.github.subapavel.jqj.RunBudgets.engine;
import static io.github.subapavel.jqj.RunBudgets.letters;
import static io.github.subapavel.jqj.RunBudgets.limits;
import static io.github.subapavel.jqj.RunBudgets.printed;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.repeat;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The run protocol at the public API (ADR-0030, ADR-0016, ADR-0017, #59): the Workspace's run state is primed by a main
 * read, begun by {@code JqFilter.run} and ended when the run ends, however it ends. Between runs every budget is
 * unlimited; a run inside a running one on the same Workspace is refused; a thread interrupt cancels a run at refuel.
 */
class RunProtocolTest {
  /** 1 048 576 letters: printed as 1 048 579 bytes, 16 drains of 64 KiB and 3 bytes, 1 025 ticks. */
  private static final int MIB = 1 << 20;

  /** {@code letters(MIB)}'s printed line, 1 048 579 bytes. */
  private static final int LINE = MIB + 3;

  /**
   * Each run starts with its whole budget. The deadline's restart is not here: it would need the clock to pass a
   * deadline between two runs, that is a sleep (CODING_STANDARDS), and the deadline reads {@code System.nanoTime()},
   * which no test can fake (ADR-0017).
   */
  @Test
  void budgetsRestartEachRun() {
    // Steps: 10 237 letters print 10 240 bytes in one drain, 10 ticks, the whole budget of each run. The document is
    // read on another engine's Workspace, so only the writer ticks on the run's Workspace.
    JqValue tenKiB = read(JqEngine.builder().build().newWorkspace(), letters(10_237)).root();
    JqEngine tenSteps = steps(10);
    JqFilter dot = tenSteps.compile(".");
    Workspace workspace = tenSteps.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    for (int run = 1; run <= 3; run++) {
      assertThat(dot.run(tenKiB, NONE, writer(workspace, target), workspace)).as("run %d of 10 ticks with 10 steps", run).isTrue();
    }
    assertThat(target.text()).as("output of three runs of 10 ticks").isEqualTo(printed(10_237).repeat(3));
    // Witness: two lines in one run are 20 ticks, over the budget.
    assertBudget(() -> dot.run(tenKiB, NONE, repeat(writer(workspace, new RecordingTarget()), 2), workspace), Budget.STEPS, "two 10-tick lines in one run with 10 steps");

    // Bytes written: null and an LF are 5 bytes, the whole budget of each run.
    JqEngine fiveBytes = bytesWritten(5);
    JqFilter nullDot = fiveBytes.compile(".");
    Workspace writing = fiveBytes.newWorkspace();
    RecordingTarget nulls = new RecordingTarget();
    for (int run = 1; run <= 3; run++) {
      assertThat(nullDot.run(JqNull.NULL, NONE, writer(writing, nulls), writing)).as("run %d writing 5 bytes with a limit of 5", run).isTrue();
    }
    assertThat(nulls.text()).as("output of three runs of 5 bytes").isEqualTo("null\nnull\nnull\n");
    // Witness: two lines in one run are 10 bytes; the second is not written.
    RecordingTarget twice = new RecordingTarget();
    assertBudget(() -> nullDot.run(JqNull.NULL, NONE, repeat(writer(writing, twice), 2), writing), Budget.BYTES_WRITTEN, "two nulls in one run with a limit of 5");
    assertThat(twice.text()).as("output of two nulls in one run with a limit of 5").isEqualTo("null\n");
  }

  @Test
  void secondRunOverOneDocumentChargesNoRead() {
    // "a" and 10 237 spaces: 10 240 bytes read, 10 ticks; printed as "a" and an LF, 4 bytes, 1 tick (jq-ref 1.8.2,
    // RunBudgets.letters).
    byte[] document = letters(1, 10_237);
    JqEngine engine = steps(10);
    JqFilter dot = engine.compile(".");
    Workspace workspace = engine.newWorkspace();

    // Witness: the run that consumes the read and prints the value takes 11 ticks.
    JqValue first = read(workspace, document).root();
    assertBudget(() -> dot.run(first, NONE, writer(workspace, new RecordingTarget()), workspace), Budget.STEPS, "reading and printing \"a\" with 10 steps");

    JqValue root = read(workspace, document).root();
    Results results = new Results();
    assertThat(dot.run(root, NONE, results, workspace)).as("run's answer for the run that consumes the read").isTrue();
    assertThat(results.values).as("results of the run that consumes the read").hasSize(1);
    // The second run over the same document, with no read in between, is charged its writer's tick only.
    RecordingTarget target = new RecordingTarget();
    assertThat(dot.run(root, NONE, writer(workspace, target), workspace)).as("run's answer for the second run over the document").isTrue();
    assertThat(target.text()).as("output of the second run over the document").isEqualTo("\"a\"\n");
  }

  /** A budget at its smallest, and the run that witnesses it inside a run. */
  private record Smallest(String name, Limits limits, Budget budget) {}

  @Test
  void writesOutsideARunAreUnbudgeted() {
    List<Smallest> budgets =
        List.of(
            new Smallest("0 steps", limits(0, Limits.UNLIMITED_TIME, Limits.UNLIMITED), Budget.STEPS),
            new Smallest("a deadline of 0", limits(Limits.UNLIMITED, Duration.ZERO, Limits.UNLIMITED), Budget.DEADLINE),
            new Smallest("0 bytes written", limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, 0), Budget.BYTES_WRITTEN));
    for (Smallest b : budgets) {
      JqEngine engine = engine(b.limits());
      JqFilter dot = engine.compile(".");
      Workspace workspace = engine.newWorkspace();

      // Before any read or run.
      RecordingTarget idle = new RecordingTarget();
      JqSink idleWriter = writer(workspace, idle);
      assertThat(idleWriter.emit(JqNull.NULL)).as("%s: emit's answer before any run", b.name()).isTrue();
      assertThat(idle.text()).as("%s: output before any run", b.name()).isEqualTo("null\n");

      // After a main read, before its run: the read (1 025 ticks) and five lines of 1 025 ticks each pass a refuel.
      JqValue mib = read(workspace, letters(MIB)).root();
      RecordingTarget primed = new RecordingTarget();
      assertThat(repeat(writer(workspace, primed), 5).emit(mib)).as("%s: emit's answer after a read", b.name()).isTrue();
      assertThat(primed.bytes().length).as("%s: bytes written after a read", b.name()).isEqualTo(5 * LINE);
      assertThat(primed.text()).as("%s: output after a read", b.name()).isEqualTo(printed(MIB).repeat(5));

      // Witness: inside a run the same budget ends it.
      if (b.budget() == Budget.DEADLINE) {
        assertBudget(() -> dot.run(mib, NONE, repeat(writer(workspace, new RecordingTarget()), 5), workspace), b.budget(), b.name() + ": five 1 MiB lines in a run");
      } else {
        assertBudget(() -> dot.run(JqNull.NULL, NONE, writer(workspace, new RecordingTarget()), workspace), b.budget(), b.name() + ": null in a run");
      }

      // After that run ended: the next lines are whole, with nothing of the ended run before them.
      RecordingTarget after = new RecordingTarget();
      JqSink afterWriter = writer(workspace, after);
      assertThat(afterWriter.emit(JqNull.NULL)).as("%s: emit's answer after a run ended", b.name()).isTrue();
      assertThat(repeat(afterWriter, 5).emit(mib)).as("%s: emit's answer for five lines after a run ended", b.name()).isTrue();
      assertThat(after.text()).as("%s: output after a run ended", b.name()).isEqualTo("null\n" + printed(MIB).repeat(5));
    }
  }

  @Test
  void reentrantRunIsIllegalState() {
    // A limit of 4 bytes: null and an LF, 5 bytes, end any run that writes them.
    JqEngine engine = bytesWritten(4);
    JqFilter dot = engine.compile(".");
    Workspace workspace = engine.newWorkspace();
    Workspace other = engine.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    JqSink outerWriter = writer(workspace, target);
    List<IllegalStateException> refused = new ArrayList<>();
    Results inner = new Results();
    Results beside = new Results();
    List<Boolean> besideAnswers = new ArrayList<>();
    JqSink sink =
        v -> {
          try {
            dot.run(v, NONE, inner, workspace);
          } catch (IllegalStateException e) {
            refused.add(e);
          }
          besideAnswers.add(dot.run(v, NONE, beside, other));
          return outerWriter.emit(v);
        };

    assertBudget(() -> dot.run(JqNull.NULL, NONE, sink, workspace), Budget.BYTES_WRITTEN, "the outer run, writing null after the refused inner run");
    assertThat(refused).as("IllegalStateExceptions of the run inside the running one").hasSize(1);
    assertThat(inner.values).as("results of the refused inner run").isEmpty();
    // Witness: a run on another Workspace inside the running one is not refused.
    assertThat(besideAnswers).as("answers of the run on another Workspace").containsExactly(true);
    assertThat(beside.values).as("results of the run on another Workspace").containsExactly(JqNull.NULL);
    // The refused run left the outer run's budget in force: its 5 bytes were not written.
    assertThat(target.bytes()).as("output of the outer run").isEmpty();

    // A run that ended, by a termination, a jq error or its sink's exception, leaves the Workspace free for the next.
    Results afterTermination = new Results();
    assertThat(dot.run(JqNull.NULL, NONE, afterTermination, workspace)).as("run's answer after a termination").isTrue();
    assertThat(afterTermination.values).as("results after a termination").containsExactly(JqNull.NULL);

    // #34: .a on [1] raises "Cannot index array with string (\"a\")".
    JqValue array = read(workspace, "[1]".getBytes(US_ASCII)).root();
    assertThatExceptionOfType(JqFilterException.class).as(".a on [1]").isThrownBy(() -> engine.compile(".a").run(array, NONE, new Results(), workspace));
    Results afterError = new Results();
    assertThat(dot.run(JqNull.NULL, NONE, afterError, workspace)).as("run's answer after a jq error").isTrue();
    assertThat(afterError.values).as("results after a jq error").containsExactly(JqNull.NULL);

    IllegalStateException thrown = new IllegalStateException("the host's sink failed");
    assertThatExceptionOfType(IllegalStateException.class)
        .as("a run whose sink throws")
        .isThrownBy(
            () ->
                dot.run(
                    JqNull.NULL,
                    NONE,
                    v -> {
                      throw thrown;
                    },
                    workspace))
        .isSameAs(thrown);
    Results afterSinkException = new Results();
    assertThat(dot.run(JqNull.NULL, NONE, afterSinkException, workspace)).as("run's answer after the sink's exception").isTrue();
    assertThat(afterSinkException.values).as("results after the sink's exception").containsExactly(JqNull.NULL);
  }

  /**
   * ADR-0017: refuel reads the interrupt flag; the run ends with {@code JqTermination(CANCELLED)} and the flag set
   * again. Each run prints nine 1 MiB lines, 9 225 ticks, so it passes a refuel after any interrupt it sees.
   */
  @Test
  void interruptedRunEndsCancelled() {
    assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag before the test").isFalse();
    JqEngine engine = unlimited();
    JqFilter dot = engine.compile(".");
    JqValue mib = read(engine.newWorkspace(), letters(MIB)).root();
    try {
      // Witness: not interrupted, the run completes and writes every line.
      Workspace free = engine.newWorkspace();
      RecordingTarget all = new RecordingTarget();
      assertThat(dot.run(mib, NONE, repeat(writer(free, all), 9), free)).as("run's answer without an interrupt").isTrue();
      assertThat(all.bytes().length).as("bytes written without an interrupt").isEqualTo(9 * LINE);
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the run without an interrupt").isFalse();

      // Interrupted before the run.
      Workspace before = engine.newWorkspace();
      RecordingTarget beforeTarget = new RecordingTarget();
      Thread.currentThread().interrupt();
      assertCancelled(() -> dot.run(mib, NONE, repeat(writer(before, beforeTarget), 9), before), "the run interrupted before it began");
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the run interrupted before it began").isTrue();
      assertThat(beforeTarget.bytes().length).as("bytes written by the run interrupted before it began").isLessThan(9 * LINE);
      assertThat(Thread.interrupted()).as("clearing the flag").isTrue();

      // Interrupted while running: the target interrupts the thread at its first write.
      Workspace during = engine.newWorkspace();
      RecordingTarget duringTarget =
          new RecordingTarget(
              n -> {
                if (n == 1) Thread.currentThread().interrupt();
              });
      assertCancelled(() -> dot.run(mib, NONE, repeat(writer(during, duringTarget), 9), during), "the run interrupted at its first write");
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the run interrupted at its first write").isTrue();
      assertThat(duringTarget.bytes().length).as("bytes written by the run interrupted at its first write").isGreaterThanOrEqualTo(64 * 1024).isLessThan(9 * LINE);
      assertThat(Thread.interrupted()).as("clearing the flag").isTrue();

      // Refuel checks the deadline before the interrupt (ADR-0030): with a 1 ns deadline the interrupted run ends on
      // its deadline, and the interrupt it did not act on stays set.
      JqEngine late = deadline(Duration.ofNanos(1));
      JqFilter lateDot = late.compile(".");
      Workspace lateWorkspace = late.newWorkspace();
      Thread.currentThread().interrupt();
      assertBudget(
          () -> lateDot.run(mib, NONE, repeat(writer(lateWorkspace, new RecordingTarget()), 9), lateWorkspace),
          Budget.DEADLINE,
          "the interrupted run with a 1 ns deadline");
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the run that ended on its deadline").isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
