package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.deadline;
import static io.github.subapavel.jqj.RunBudgets.engine;
import static io.github.subapavel.jqj.RunBudgets.letters;
import static io.github.subapavel.jqj.RunBudgets.limits;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.repeat;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The deadline at the public API (ADR-0030, ADR-0017, F-36, #59): the run's begin reads the clock once
 * ({@code System.nanoTime()}, not the host's) as the deadline's start, and refuel, every 4096 ticks, is the only place
 * that checks the deadline. A deadline of 0 or 1 ns has passed by the time refuel checks it, so a run that reaches a refuel
 * ends with {@code JqTermination(BUDGET, DEADLINE)} and a run that does not completes. No test sleeps (CODING_STANDARDS): the clock cannot be faked here, so no case depends on how long a run
 * takes beyond the nanosecond.
 */
class DeadlineTest {
  /** 1 048 576 letters: read as 1 048 578 bytes, 1 025 ticks; printed as 1 048 579 bytes, 16 drains of 64 KiB and 3 bytes, 1 025 ticks. */
  private static final int MIB = 1 << 20;

  /** {@code letters(MIB)}'s printed line, 1 048 579 bytes. */
  private static final int LINE = MIB + 3;

  @Test
  void deadlineEndsAtRefuel() {
    // The document is read on another engine's Workspace, so the run's ticks are its writer's only: nine lines of
    // 1 025 ticks, 9 225 ticks, past the refuel interval twice.
    JqValue mib = read(JqEngine.builder().build().newWorkspace(), letters(MIB)).root();

    JqEngine engine = deadline(Duration.ofNanos(1));
    Workspace workspace = engine.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    assertBudget(
        () -> engine.compile(".").run(mib, NONE, repeat(writer(workspace, target), 9), workspace), Budget.DEADLINE, "nine 1 MiB lines with a 1 ns deadline");
    assertThat(target.bytes().length).as("bytes written before the deadline ended the run").isLessThan(9 * LINE);

    // Witness: with an unlimited deadline the same run completes and writes every line.
    JqEngine unlimitedEngine = unlimited();
    Workspace free = unlimitedEngine.newWorkspace();
    RecordingTarget all = new RecordingTarget();
    assertThat(unlimitedEngine.compile(".").run(mib, NONE, repeat(writer(free, all), 9), free)).as("run's answer with an unlimited deadline").isTrue();
    assertThat(all.bytes().length).as("bytes written with an unlimited deadline").isEqualTo(9 * LINE);

    // Refuel checks the step budget before the deadline (ADR-0030): with 100 steps as well, the first refuel passes
    // both, and the run ends on its steps.
    JqEngine both = engine(limits(100, Duration.ofNanos(1), Limits.UNLIMITED));
    Workspace stepped = both.newWorkspace();
    assertBudget(
        () -> both.compile(".").run(mib, NONE, repeat(writer(stepped, new RecordingTarget()), 9), stepped),
        Budget.STEPS,
        "nine 1 MiB lines with 100 steps and a 1 ns deadline");
  }

  @Test
  void deadlineIsCheckedOnlyAtRefuel() {
    for (Duration passed : List.of(Duration.ZERO, Duration.ofNanos(1))) {
      JqEngine engine = deadline(passed);
      JqFilter dot = engine.compile(".");
      Workspace workspace = engine.newWorkspace();

      // No tick at all.
      Results results = new Results();
      assertThat(dot.run(JqNull.NULL, NONE, results, workspace)).as("deadline %s: run's answer without a tick", passed).isTrue();
      assertThat(results.values).as("deadline %s: results without a tick", passed).containsExactly(JqNull.NULL);

      // 1 MiB read and printed on this Workspace: 2 050 ticks, under the refuel interval, so the deadline is never checked.
      JqValue mib = read(workspace, letters(MIB)).root();
      RecordingTarget target = new RecordingTarget();
      assertThat(dot.run(mib, NONE, writer(workspace, target), workspace)).as("deadline %s: run's answer for 2 050 ticks", passed).isTrue();
      assertThat(target.bytes().length).as("deadline %s: bytes written for 2 050 ticks", passed).isEqualTo(LINE);

      // Witness: the same document printed five times in one run, 1 025 + 5 125 ticks, passes a refuel and ends.
      JqValue again = read(workspace, letters(MIB)).root();
      assertBudget(
          () -> dot.run(again, NONE, repeat(writer(workspace, new RecordingTarget()), 5), workspace),
          Budget.DEADLINE,
          "deadline " + passed + ": five 1 MiB lines");
    }
  }
}
