package dev.rail8.jq;

import static dev.rail8.jq.CallRuns.F;
import static dev.rail8.jq.CallRuns.onStack;
import static dev.rail8.jq.CallRuns.texts;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Cause;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The stack backstop at the public API (#107 "Stack overflow", ADR-0045 #107 amendment: only {@code Executable.run}
 * turns a {@code StackOverflowError} into {@code Termination(STACK)}, a backstop until S2b.4 and S2b.5). jq-ref 1.8.2
 * completes F on 10^7 (N up to 10^7 prints N, rc 0), so the end here is the engine's.
 *
 * <p>The run's thread has a 1 MiB stack while the limits declare {@code stackBytes} of 1 GiB and no recursion-depth
 * limit, so neither the depth budget nor the driver that S2b.5 derives from the declared stack (ADR-0046, {@code U_f})
 * stops F first: the real stack runs out. {@code stackBytes} is set as the last component of {@link Limits}; nothing
 * reads it before S2b.5.
 */
class StackBackstopTest {
  private static final long MIB = 1L << 20;

  @Test
  void stackBackstopIsTermination() {
    Limits cli = Limits.cli();
    JqEngine engine =
        JqEngine.builder()
            .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), Integer.MAX_VALUE, Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), 1L << 30))
            .build();
    AtomicReference<Throwable> ended = new AtomicReference<>();
    AtomicReference<List<String>> after = new AtomicReference<>();
    onStack(
        MIB,
        () -> {
          Workspace workspace = engine.newWorkspace();
          try {
            texts(engine.compile(F), workspace, "10000000");
          } catch (Throwable t) {
            ended.set(t);
          }
          // The Workspace is free again after the termination.
          after.set(texts(engine.compile("."), workspace, "null"));
        });
    assertThat(ended.get()).as("what F on 10^7 ended with on a 1 MiB stack").isInstanceOf(JqTermination.class);
    JqTermination t = (JqTermination) ended.get();
    assertThat(t.cause()).as("cause").isEqualTo(Cause.STACK);
    assertThat(t.budget()).as("budget").isNull();
    assertThat(t.exitCode()).as("exit code").isEqualTo(5);
    assertThat(t.getMessage()).as("message").isEqualTo("Stack exhausted");
    assertThat(after.get()).as("results of . on null on the same Workspace").containsExactly("null");
  }
}
