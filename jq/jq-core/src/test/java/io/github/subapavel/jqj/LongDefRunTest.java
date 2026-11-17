package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DEVIATIONS "Long definition runs" (F-44): jq-ref 1.8.2 (observed 2026-11-13, the filter read with {@code -f})
 * prints {@code [0,9991]} for {@code def f0: 0; …; def f9991: 9991; [f0, f9991]} and rejects 9 993 such definitions
 * with {@code jq: error: memory exhausted at <top-level>, line 1, column 167660}, rc 3. jqj binds and lowers a run of
 * definitions by loops, so it compiles and runs the first rejected run and one of 10⁵, on ADR-0017's 512 KiB stack.
 */
class LongDefRunTest {

  private static final long STACK = 512 * 1024;

  @ParameterizedTest(name = "{0} definitions on a 512 KiB thread")
  @ValueSource(ints = {9_993, 100_000})
  void aRunPastJqsParserStackCompilesAndRuns(int definitions) throws InterruptedException {
    StringBuilder f = new StringBuilder();
    for (int i = 0; i < definitions; i++) {
      f.append("def f").append(i).append(": ").append(i).append("; ");
    }
    String filter = f.append("[f0, f").append(definitions - 1).append(']').toString();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<List<String>> got = new AtomicReference<>();
    Thread small = new Thread(null, () -> {
      try {
        got.set(results(filter));
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "512 KiB", STACK);
    small.start();
    small.join();
    assertThat(failure.get()).as("what %d definitions threw on a 512 KiB thread", definitions).isNull();
    assertThat(got.get()).as("results of %d definitions on null", definitions).isEqualTo(List.of("[0," + (definitions - 1) + "]"));
  }
}
