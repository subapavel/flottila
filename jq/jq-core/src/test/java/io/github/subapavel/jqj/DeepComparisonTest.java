package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Comparison depth on built values (ADR-0029, ADR-0037, GLOSSARY "Comparison depth"): jvp_cmp raises the catchable
 * "Comparison too deep" when a pair lies deeper than 10 000, its check first on every call; an array's elements are one
 * level below it, an object's key list one and each key two. So {@code . < .} on N arrays around null compares at
 * N = 10 000 and raises at 10 001, and on N objects {@code {"a": …}} around null compares at 9 999 and raises at 10 000.
 * Order runs on an explicit stack, never Java recursion: at 10⁵ levels, on a thread whose stack holds far fewer than
 * 10 001 frames of a recursive compare, the run still ends with the jq error, never a StackOverflowError or a
 * termination. No filter of spec 2a builds that depth in one run (no reduce yet, and jq's grammar refuses so deep a
 * filter, #81), so each run wraps its input, a built value, in 100 levels more (DeepBuiltArrayTest). The oracle is jq-ref
 * 1.8.2, observed 2026-11-13: {@code reduce range(N) as $i (null; [.]) | . < .} gives false for N = 10 000 and "Comparison
 * too deep" (rc 5) for 10 001 and 100 000; {@code reduce range(N) as $i (null; {a:.}) | . < .} false for 9 999 and the
 * error for 10 000 and 100 000. The same boundaries over documents are diff/compare-depth.cases.
 */
class DeepComparisonTest {
  /** A stack of 256 KiB: a recursive compare 10 001 frames deep needs more. */
  private static final long SMALL_STACK = 256 * 1024;

  private final JqEngine engine = unlimited();
  private final Workspace workspace = engine.newWorkspace();

  @Test
  void arraysCompareAtTenThousandLevelsAndRaiseOneLevelDeeper() {
    JqValue tenThousand = wrap(JqNull.NULL, "[", "]", 10_000);
    assertThat(compare(tenThousand)).as(". < . on 10 000 arrays around null").containsExactly("false");
    assertTooDeep(wrap(tenThousand, "[", "]", 1), "10 001 arrays around null");
  }

  @Test
  void objectsCompareAtNineThousandNineHundredNinetyNineLevelsAndRaiseOneLevelDeeper() {
    JqValue below = wrap(JqNull.NULL, "{a:", "}", 9_999);
    assertThat(compare(below)).as(". < . on 9 999 objects around null").containsExactly("false");
    assertTooDeep(wrap(below, "{a:", "}", 1), "10 000 objects around null");
  }

  @Test
  void hundredThousandNestedArraysRaiseOnASmallStack() {
    assertTooDeepOnASmallStack(wrap(JqNull.NULL, "[", "]", 100_000), "10⁵ arrays around null");
  }

  @Test
  void hundredThousandNestedObjectsRaiseOnASmallStack() {
    assertTooDeepOnASmallStack(wrap(JqNull.NULL, "{a:", "}", 100_000), "10⁵ objects around null");
  }

  /** {@code value} inside {@code levels} more levels of {@code open … close}, 100 at most per run. */
  private JqValue wrap(JqValue value, String open, String close, int levels) {
    JqValue result = value;
    for (int left = levels; left > 0; left -= 100) {
      int n = Math.min(left, 100);
      Results results = new Results();
      assertThat(engine.compile(open.repeat(n) + "." + close.repeat(n)).run(result, NONE, results, workspace)).as("answer of a wrap run").isTrue();
      assertThat(results.values).as("results of a wrap run").hasSize(1);
      result = results.values.getFirst();
    }
    return result;
  }

  /** The texts of {@code . < .} on {@code value}. */
  private List<String> compare(JqValue value) {
    Results results = new Results();
    assertThat(engine.compile(". < .").run(value, NONE, results, engine.newWorkspace())).as("answer of . < .").isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /** {@code . < .} on {@code value} raises jq's catchable "Comparison too deep" and gives no result. */
  private void assertTooDeep(JqValue value, String what) {
    Throwable thrown = run(value);
    assertComparisonTooDeep(thrown, what);
  }

  /** {@link #assertTooDeep} on a thread with {@link #SMALL_STACK}: any other end, a StackOverflowError included, fails. */
  private void assertTooDeepOnASmallStack(JqValue value, String what) {
    JqFilter filter = engine.compile(". < .");
    Workspace own = engine.newWorkspace();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                filter.run(value, NONE, new Results(), own);
              } catch (Throwable t) {
                thrown.set(t);
              }
            },
            "small-stack compare",
            SMALL_STACK);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    assertComparisonTooDeep(thrown.get(), what + " on a 256 KiB stack");
  }

  private Throwable run(JqValue value) {
    JqFilter filter = engine.compile(". < .");
    Results results = new Results();
    try {
      filter.run(value, NONE, results, engine.newWorkspace());
    } catch (JqException e) {
      assertThat(results.values).as("results before the error").isEmpty();
      return e;
    }
    return null;
  }

  private static void assertComparisonTooDeep(Throwable thrown, String what) {
    assertThat(thrown).as(". < . on %s", what).isInstanceOf(JqFilterException.class);
    JqValue error = ((JqFilterException) thrown).value();
    assertThat(error).as("error value of . < . on %s", what).isInstanceOf(JqString.class);
    assertThat(((JqString) error).value()).as("error text of . < . on %s", what).isEqualTo("Comparison too deep");
  }
}
