package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deep built values and runs on a small stack, for the depth limits of ADR-0029 at the public API. No filter of spec 2a
 * builds 10 000 levels in one run (no reduce before #69, and jqj's parser nests at most 127 objects or arrays, #81), so
 * a value is wrapped by repeated runs of a 100-level filter, each run's result the next one's input, as
 * DeepBuiltArrayTest does. Each depth run happens on a thread with a 256 KiB stack: Java recursion per level would end
 * there as a StackOverflowError long before 10 000 levels, so a result or jq's error shows the explicit stack.
 */
final class DeepRuns {
  private DeepRuns() {}

  /** The stack of the thread every depth run uses. */
  static final long SMALL_STACK = 256 * 1024;

  private static final int PER_RUN = 100;

  static final JqEngine ENGINE = RunBudgets.unlimited();

  /** The one result of {@code filter} on null: the innermost value of a chain. */
  static JqValue value(String filter) {
    Results results = new Results();
    assertThat(ENGINE.compile(filter).run(JqNull.NULL, NONE, results, ENGINE.newWorkspace())).as("run's answer for %s", filter).isTrue();
    assertThat(results.values).as("results of %s", filter).hasSize(1);
    return results.values.getFirst();
  }

  /**
   * {@code start} wrapped {@code levels} times in {@code open} and {@code close} ({@code [} and {@code ]}, or
   * {@code {a:} and {@code }}): one new built container per level.
   */
  static JqValue wrap(JqValue start, String open, String close, int levels) {
    return repeat(start, n -> open.repeat(n) + "." + close.repeat(n), levels);
  }

  /**
   * Each half of the pair {@code start} ({@code [x, y]}) wrapped {@code levels} times in {@code open} and {@code close},
   * each level of each half its own built container, so the two halves are equal but share no container.
   */
  static JqValue wrapPair(JqValue start, String open, String close, int levels) {
    return repeat(
        start,
        n -> "[" + open.repeat(n) + ".[0]" + close.repeat(n) + "," + open.repeat(n) + ".[1]" + close.repeat(n) + "]",
        levels);
  }

  private static JqValue repeat(JqValue start, java.util.function.IntFunction<String> nest, int levels) {
    JqFilter hundred = ENGINE.compile(nest.apply(PER_RUN));
    JqFilter one = ENGINE.compile(nest.apply(1));
    JqValue value = start;
    for (int i = 0; i < levels / PER_RUN; i++) {
      value = single(hundred, value);
    }
    for (int i = 0; i < levels % PER_RUN; i++) {
      value = single(one, value);
    }
    return value;
  }

  private static JqValue single(JqFilter filter, JqValue in) {
    Results results = new Results();
    assertThat(filter.run(in, NONE, results, ENGINE.newWorkspace())).as("a wrapping run's answer").isTrue();
    assertThat(results.values).as("results of a wrapping run").hasSize(1);
    return results.values.getFirst();
  }

  /**
   * Runs {@code filter} on {@code in} on a thread with a {@link #SMALL_STACK} stack and gives what it ended with: the
   * list of its results, or what it threw (a StackOverflowError included).
   */
  static Object onSmallStack(String filter, JqValue in) {
    JqFilter compiled = ENGINE.compile(filter);
    AtomicReference<Object> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                Results results = new Results();
                compiled.run(in, NONE, results, ENGINE.newWorkspace());
                outcome.set(results.values);
              } catch (Throwable t) {
                outcome.set(t);
              }
            },
            "small-stack",
            SMALL_STACK);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return outcome.get();
  }

  /** Asserts that {@code outcome} is one result whose compact text is {@code text}. */
  static void assertResult(Object outcome, String text, String what) {
    assertThat(outcome).as("outcome of %s", what).isInstanceOf(List.class);
    List<?> values = (List<?>) outcome;
    assertThat(values).as("results of %s", what).hasSize(1);
    assertThat(((JqValue) values.getFirst()).toJson()).as("result of %s", what).isEqualTo(text);
  }

  /** Asserts that {@code outcome} is jq's catchable error with the message {@code message}, never a stack overflow. */
  static void assertJqError(Object outcome, String message, String what) {
    assertThat(outcome).as("outcome of %s", what).isInstanceOf(JqFilterException.class);
    JqValue value = ((JqFilterException) outcome).value();
    assertThat(value).as("error value of %s", what).isInstanceOf(JqString.class);
    assertThat(((JqString) value).value()).as("error text of %s", what).isEqualTo(message);
  }
}
