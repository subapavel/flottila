package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesWritten;
import static io.github.subapavel.jqj.RunBudgets.letters;
import static io.github.subapavel.jqj.RunBudgets.printed;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The bytes-written budget at the public API (ADR-0030, ADR-0016, #59), the sink count on {@code .}: the writer charges
 * each drain of its 64 KiB buffer (ADR-0027) before it writes it, and a drain that would pass the limit is not written,
 * so the output never exceeds the budget and the run ends with {@code JqTermination(BUDGET, BYTES_WRITTEN)}.
 */
class BytesWrittenBudgetTest {
  /** ADR-0027: the Workspace's print buffer; a line longer than it drains in pieces of exactly this size, then the rest. */
  private static final int BUFFER = 64 * 1024;

  /** A limit, and what a run of {@code .} with the compact writer writes under it. */
  private record Case(long limit, int written, boolean completes) {}

  @Test
  void bytesWrittenStopBeforeTheLimit() {
    // 70 000 letters print 70 003 bytes (RunBudgets.letters), which drain as 65 536 and then 4 467 bytes
    // (JsonWriterTest.ticksCountReadAndPrintedKiB).
    String line = printed(70_000);
    for (Case c :
        new Case[] {
          new Case(70_003, 70_003, true),
          new Case(70_002, 65_536, false),
          new Case(65_537, 65_536, false),
          new Case(65_536, 65_536, false),
          new Case(65_535, 0, false),
          new Case(0, 0, false)
        }) {
      JqEngine engine = bytesWritten(c.limit());
      Workspace workspace = engine.newWorkspace();
      JqValue root = read(workspace, letters(70_000)).root();
      RecordingTarget target = new RecordingTarget();
      String what = "the 70 003-byte line with a limit of " + c.limit();
      if (c.completes()) {
        assertThat(engine.compile(".").run(root, NONE, writer(workspace, target), workspace)).as("run's answer for %s", what).isTrue();
      } else {
        assertBudget(() -> engine.compile(".").run(root, NONE, writer(workspace, target), workspace), Budget.BYTES_WRITTEN, what);
      }
      assertThat(target.bytes().length).as("bytes written for %s", what).isEqualTo(c.written());
      assertThat(target.text()).as("output for %s", what).isEqualTo(line.substring(0, c.written()));
    }

    // null and an LF, 5 bytes in one drain (jq-ref 1.8.2: null under -c .): a limit of 5 writes them, 4 nothing.
    JqEngine five = bytesWritten(5);
    Workspace fits = five.newWorkspace();
    RecordingTarget whole = new RecordingTarget();
    assertThat(five.compile(".").run(JqNull.NULL, NONE, writer(fits, whole), fits)).as("run's answer for null with a limit of 5").isTrue();
    assertThat(whole.text()).as("output for null with a limit of 5").isEqualTo("null\n");
    JqEngine four = bytesWritten(4);
    Workspace over = four.newWorkspace();
    RecordingTarget nothing = new RecordingTarget();
    assertBudget(() -> four.compile(".").run(JqNull.NULL, NONE, writer(over, nothing), over), Budget.BYTES_WRITTEN, "null with a limit of 4");
    assertThat(nothing.bytes()).as("output for null with a limit of 4").isEmpty();
    assertThat(nothing.writes).as("write calls for null with a limit of 4").isZero();
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 100);
  }

  /**
   * Whatever the limit, the bytes written are a prefix of the line and never more than the limit. A line within the
   * limit is written whole; any other run ends with BYTES_WRITTEN after whole buffers only (the held-back drain is at
   * most one buffer), so it stops at the last buffer that fits. Limits are drawn anywhere, next to a buffer multiple and
   * next to the line's length.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void bytesWrittenNeverPassTheLimit(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    int letters = random.nextInt(0, 300_000);
    String line = printed(letters);
    long length = line.length();
    long limit =
        Math.max(
            0,
            switch (random.nextInt(3)) {
              case 0 -> random.nextLong(0, length + 70_000);
              case 1 -> (long) BUFFER * random.nextInt(0, 6) + random.nextInt(-1, 2);
              default -> length + random.nextInt(-1, 2);
            });
    String what = String.format("seed %d: a %d-byte line with a limit of %d", seed, length, limit);

    JqEngine engine = bytesWritten(limit);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, letters(letters)).root();
    RecordingTarget target = new RecordingTarget();
    if (length <= limit) {
      assertThat(engine.compile(".").run(root, NONE, writer(workspace, target), workspace)).as("%s: run's answer", what).isTrue();
      assertThat(target.text()).as("%s: output", what).isEqualTo(line);
    } else {
      assertBudget(() -> engine.compile(".").run(root, NONE, writer(workspace, target), workspace), Budget.BYTES_WRITTEN, what);
      int written = target.bytes().length;
      assertThat((long) written).as("%s: bytes written", what).isLessThanOrEqualTo(limit).isGreaterThan(limit - BUFFER);
      assertThat(written % BUFFER).as("%s: bytes written past whole buffers", what).isZero();
      assertThat(line).as("%s: the line starts with the output", what).startsWith(target.text());
    }
  }
}
