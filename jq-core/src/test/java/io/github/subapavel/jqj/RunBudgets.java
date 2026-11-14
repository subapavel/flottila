package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.JqTermination.Cause;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * The run protocol's fixtures (ADR-0030, #59), through the exported API only: engines with one budget set, documents
 * whose read and printed KiB are known, a sink that keeps its results, and the checks of a run's termination.
 */
final class RunBudgets {
  private RunBudgets() {}

  /** No variable is declared by {@code .}, so every run binds none. */
  static final Bindings NONE = Bindings.builder().build();

  /**
   * ADR-0030 and the design approval of #59: jq has no budgets, so the messages are jqj's own. Bytes built: ADR-0035
   * and the design approval of #64.
   */
  private static final Map<Budget, String> MESSAGES =
      Map.of(
          Budget.STEPS, "Step budget exceeded",
          Budget.DEADLINE, "Deadline exceeded",
          Budget.BYTES_BUILT, "Bytes-built budget exceeded",
          Budget.BYTES_WRITTEN, "Bytes-written budget exceeded");

  /**
   * {@link Limits#cli()}, whose steps, deadline, bytes built and bytes written are unlimited, with the steps, deadline
   * and bytes written given.
   */
  static Limits limits(long steps, Duration deadline, long bytesWritten) {
    Limits cli = Limits.cli();
    return new Limits(
        steps, deadline, cli.nestingDepth(), cli.recursionDepth(), Limits.UNLIMITED, bytesWritten, cli.regexMatchTime(), cli.stackBytes());
  }

  static JqEngine engine(Limits limits) {
    return JqEngine.builder().limits(limits).build();
  }

  /** An engine whose only budget is {@code steps}. */
  static JqEngine steps(long steps) {
    return engine(limits(steps, Limits.UNLIMITED_TIME, Limits.UNLIMITED));
  }

  /** An engine whose only budget is {@code deadline}. */
  static JqEngine deadline(Duration deadline) {
    return engine(limits(Limits.UNLIMITED, deadline, Limits.UNLIMITED));
  }

  /** An engine whose only budget is {@code bytesWritten}. */
  static JqEngine bytesWritten(long bytesWritten) {
    return engine(limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, bytesWritten));
  }

  /** An engine whose only budget is {@code bytesBuilt} (ADR-0035). */
  static JqEngine bytesBuilt(long bytesBuilt) {
    Limits cli = Limits.cli();
    return engine(
        new Limits(
            Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), cli.recursionDepth(), bytesBuilt, Limits.UNLIMITED, cli.regexMatchTime(), cli.stackBytes()));
  }

  /** An engine whose steps, deadline and bytes written are unlimited. */
  static JqEngine unlimited() {
    return engine(limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, Limits.UNLIMITED));
  }

  /**
   * A string of {@code k} letters {@code a}, quotes included: k + 2 bytes. jq-ref 1.8.2, observed 2026-11-12 for k = 0,
   * 1, 1 021, 10 237, 10 238, 65 533, 65 534, 70 000, 299 999, 1 048 576 and 8 388 608: {@code jq-ref -c .} prints it
   * unchanged and an LF, k + 3 bytes, rc 0; also with spaces after it (1 letter and 10 237 spaces: 4 bytes; 998 and
   * 2 000: 1 001; 40 000 and 30 000: 40 003).
   */
  static byte[] letters(int k) {
    byte[] bytes = new byte[k + 2];
    Arrays.fill(bytes, (byte) 'a');
    bytes[0] = '"';
    bytes[k + 1] = '"';
    return bytes;
  }

  /** {@link #letters(int)} followed by {@code spaces} spaces. */
  static byte[] letters(int k, int spaces) {
    byte[] bytes = Arrays.copyOf(letters(k), k + 2 + spaces);
    Arrays.fill(bytes, k + 2, bytes.length, (byte) ' ');
    return bytes;
  }

  /** What jq-ref prints for {@link #letters(int)} under {@code -c .}: the string and an LF. */
  static String printed(int k) {
    return "\"" + "a".repeat(k) + "\"\n";
  }

  /**
   * {@code {"":0,"AaAaAaAa":1,…,"BBBBBBBB":16}}: the empty key, then the 16 keys of four blocks, {@code BB} where bit b of
   * i is set and {@code Aa} where it is not, high bit first, so in ascending order. The 16 share one 31-polynomial, so
   * the key proof's table runs out of probes and sorts the 17 (ADR-0044, KeyProofTest). jq-ref 1.8.2, observed
   * 2026-11-13: {@code -c .} prints it unchanged and {@code length} gives 17.
   */
  static String colliding() {
    StringBuilder json = new StringBuilder("{\"\":0");
    for (int i = 0; i < 16; i++) {
      json.append(",\"");
      for (int b = 3; b >= 0; b--) json.append((i >> b & 1) == 1 ? "BB" : "Aa");
      json.append("\":").append(i + 1);
    }
    return json.append('}').toString();
  }

  static JsonDoc read(Workspace workspace, byte[] json) {
    return workspace.read(MemorySegment.ofArray(json));
  }

  /** The compact writer over {@code workspace} into {@code target}. */
  static JqSink writer(Workspace workspace, RecordingTarget target) {
    return JsonWriter.sink(workspace, target, OutputOptions.compact());
  }

  /** A sink that hands each result to {@code sink} {@code times} times, so one run of {@code .} prints several lines. */
  static JqSink repeat(JqSink sink, int times) {
    return v -> {
      for (int i = 0; i < times; i++) {
        if (!sink.emit(v)) {
          return false;
        }
      }
      return true;
    };
  }

  /** A sink that keeps every result and never stops; it neither writes nor ticks. */
  static final class Results implements JqSink {
    final List<JqValue> values = new ArrayList<>();

    @Override
    public boolean emit(JqValue v) {
      values.add(v);
      return true;
    }
  }

  /**
   * Asserts that {@code run} ends with {@code JqTermination(BUDGET, budget)}: exit code 5 and the budget's message
   * (ADR-0030).
   */
  static JqTermination assertBudget(ThrowingCallable run, Budget budget, String what) {
    JqTermination t = assertThatExceptionOfType(JqTermination.class).as("%s", what).isThrownBy(run).actual();
    assertThat(t.cause()).as("cause of %s", what).isEqualTo(Cause.BUDGET);
    assertThat(t.budget()).as("budget of %s", what).isEqualTo(budget);
    assertThat(t.exitCode()).as("exit code of %s", what).isEqualTo(5);
    assertThat(t.getMessage()).as("message of %s", what).isEqualTo(MESSAGES.get(budget));
    return t;
  }

  /** Asserts that {@code run} ends with {@code JqTermination(CANCELLED)}: no budget, exit code 5 (ADR-0017, ADR-0030). */
  static void assertCancelled(ThrowingCallable run, String what) {
    JqTermination t = assertThatExceptionOfType(JqTermination.class).as("%s", what).isThrownBy(run).actual();
    assertThat(t.cause()).as("cause of %s", what).isEqualTo(Cause.CANCELLED);
    assertThat(t.budget()).as("budget of %s", what).isNull();
    assertThat(t.exitCode()).as("exit code of %s", what).isEqualTo(5);
  }
}
