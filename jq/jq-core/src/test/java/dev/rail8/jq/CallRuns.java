package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqValue;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The fixtures of #107's call tests (ADR-0045 and its #107 amendment, ADR-0046), through the exported API only: the
 * recursive filter F of the ticket, a run on a document that keeps its results as text, and a run on a thread of a
 * given stack.
 */
final class CallRuns {
  private CallRuns() {}

  /**
   * F: one live Call per level, the outer call included, so input n holds n + 1 activations at its deepest. jq-ref
   * 1.8.2 gives 99 on 99 and 100 on 100 (diff/calls.rec: calls/depth-99, calls/depth-100), and N for N up to 10^7
   * (F-87).
   */
  static final String F = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; f";

  /**
   * ADR-0046 "Where the stack is tested": an in-suite run gets a thread of at least four times the declared
   * {@code stackBytes}, 512 KiB in {@link Limits#cli()}; 8 MiB keeps a thousand live calls on the stack before the
   * driver of S2b.4 and S2b.5 exists.
   */
  static final long STACK = 8L << 20;

  /**
   * The result texts of {@code filter} on the document {@code json}, read on {@code workspace} just before the run;
   * asserts that the results ran out.
   */
  static List<String> texts(JqFilter filter, Workspace workspace, String json) {
    JqValue root = read(workspace, json.getBytes(US_ASCII)).root();
    Results results = new Results();
    assertThat(filter.run(root, NONE, results, workspace)).as("run's answer on %s", json).isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /** What a body run on a thread of a given stack may throw. */
  @FunctionalInterface
  interface Body {
    void run() throws Exception;
  }

  /**
   * Runs {@code body} on a new thread of {@code stackBytes} and waits for it; what it threw is thrown here: an error or
   * a runtime exception as it is (an AssertionError of the body included), any other exception in an AssertionError.
   */
  static void onStack(long stackBytes, Body body) {
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                body.run();
              } catch (Throwable t) {
                thrown.set(t);
              }
            },
            "calls-" + stackBytes,
            stackBytes);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    Throwable t = thrown.get();
    if (t instanceof Error e) {
      throw e;
    }
    if (t instanceof RuntimeException r) {
      throw r;
    }
    if (t != null) {
      throw new AssertionError(t);
    }
  }
}
