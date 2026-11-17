package dev.rail8.jq.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rail8.jq.JqTermination;
import dev.rail8.jq.Limits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A run the engine ends with a termination ends the command line with jq's error line and exit code 5 (#197, F-87,
 * ADR-0017 "Exit codes"): the results before it are flushed first, the line is {@code jq: error: } and the
 * termination's message, without jq's {@code (at <file>:N)}. Only {@link Main#run(Invocation)} and its limits seam end
 * so; the builder hook entry keeps throwing (ADR-0020).
 */
class MainTerminationTest {

  /** A stream that logs each flush and each write, as {@code <name> flush} and {@code <name> <text>}. */
  private static OutputStream logging(String name, List<String> log) {
    return new OutputStream() {
      @Override
      public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) {
        log.add(name + " " + new String(b, off, len, UTF_8));
      }

      @Override
      public void flush() {
        log.add(name + " flush");
      }
    };
  }

  private static Limits cliWith(long steps, long bytesBuilt) {
    Limits cli = Limits.cli();
    return new Limits(
        steps, cli.deadline(), cli.nestingDepth(), cli.recursionDepth(), bytesBuilt, cli.bytesWritten(), cli.regexMatchTime(), cli.stackBytes());
  }

  private static Invocation compactOnNull(String filter, OutputStream out, OutputStream err) {
    return new Invocation(List.of("-c", filter), Map.of(), Path.of("."), new ByteArrayInputStream("null\n".getBytes(UTF_8)), out, err);
  }

  /** What reached stdout: its writes in the log, joined. */
  private static String stdout(List<String> log) {
    StringBuilder text = new StringBuilder();
    for (String entry : log) {
      if (entry.startsWith("out ") && !entry.equals("out flush")) {
        text.append(entry, 4, entry.length());
      }
    }
    return text.toString();
  }

  @Test
  void aStepTerminationFlushesTheResultsWritesJqsLineAndExitsFive() {
    // jq-ref 1.8.2, observed 2026-11-14: printf 'null\n' | jq-ref -c '1, (range(1000000) | empty)' -> rc 0, stdout 1
    // and LF. jqj's 1 000 steps let the read and the first result through and end inside the range.
    List<String> log = new ArrayList<>();
    Invocation invocation = compactOnNull("1, (range(1000000) | empty)", logging("out", log), logging("err", log));

    int rc = Main.run(invocation, cliWith(1_000, Limits.UNLIMITED));

    assertThat(rc).as("exit code").isEqualTo(5);
    assertThat(stdout(log)).as("stdout").isEqualTo("1\n");
    assertThat(log.subList(log.size() - 2, log.size()))
        .as("the last two events: stdout flushed, then the one stderr line")
        .containsExactly("out flush", "err jq: error: Step budget exceeded\n");
  }

  @Test
  void aBytesBuiltTerminationFlushesTheResultsWritesJqsLineAndExitsFive() {
    // jq-ref 1.8.2, observed 2026-11-14: printf 'null\n' | jq-ref -c '1, ([range(1000)] | empty)' -> rc 0, stdout 1
    // and LF. jqj's 100 bytes built end inside the array of 1 000 slots (DEVIATIONS "Array length limit").
    List<String> log = new ArrayList<>();
    Invocation invocation = compactOnNull("1, ([range(1000)] | empty)", logging("out", log), logging("err", log));

    int rc = Main.run(invocation, cliWith(Limits.UNLIMITED, 100));

    assertThat(rc).as("exit code").isEqualTo(5);
    assertThat(stdout(log)).as("stdout").isEqualTo("1\n");
    assertThat(log.subList(log.size() - 2, log.size()))
        .as("the last two events: stdout flushed, then the one stderr line")
        .containsExactly("out flush", "err jq: error: Bytes-built budget exceeded\n");
  }

  @Test
  void theBuilderHookEntryStillThrowsTheTermination() {
    // ADR-0020: CompileModes.cli reads the cause of a termination from run(Invocation, UnaryOperator).
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation = compactOnNull("1, (range(1000000) | empty)", new ByteArrayOutputStream(), err);
    Limits steps = cliWith(1_000, Limits.UNLIMITED);

    assertThatThrownBy(() -> Main.run(invocation, b -> b.limits(steps)))
        .as("the run through the hook entry")
        .isInstanceOfSatisfying(JqTermination.class, t -> assertThat(t.budget()).as("budget").isEqualTo(JqTermination.Budget.STEPS));
    assertThat(err.toString(UTF_8)).as("stderr of the hook entry").isEmpty();
  }
}
