package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqValue;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A document number's reads tick (#97, F-50; ADR-0008 "Amendment (ticket #97, F-50)", ADR-0034's scalar rule and
 * Equality bullet, ADR-0037 "Ticks"): a native read of a non-short view's double ticks once per started KiB of its
 * token, each operand once per call; an exact compare ticks both its tokens, in Equality as in Order; {@code -x} and
 * {@code length} tick at the same rate. A short literal, a literal number's cached double, a computed number and the
 * host accessor {@code doubleValue()} tick nothing, and no double is cached per token.
 *
 * <p>The document is {@code [1.0…0]}: its one element equals 1 and is a token of n bytes, so the document has n + 2
 * bytes. It is read on the run's own Workspace, so the run keeps the read's one tick per started KiB of the document
 * (ADR-0015, StepBudgetTest): 2 at n = 1 024 and 1 025, 1 025 at n = 1 MiB. Today's count of each filter, observed on
 * the base 460ad6f by bisection, is that read and the sites the filter shares with any input: {@code .[0]} steps over
 * no element, a constant array of k elements costs its k - 1 commas (NativeStepsTest), {@code range} one per value
 * (RangeStepsTest), and the slice {@code [5,6,7] | .[1:]} 2 more. Each expected count is today's plus the token's
 * started KiB, 1 at n = 1 024 and 2 at n = 1 025 (1 024 at 1 MiB), and twice that where the rule reads two tokens.
 *
 * <p>Each count is an adjacent pair: N steps complete with jq-ref's result and N - 1 end with
 * {@code JqTermination(BUDGET, STEPS)}. Every result is jq-ref 1.8.2's, observed 2026-11-14 with {@code jq-ref -c} on
 * the same filter over a synthetic file of the same bytes, the 1 MiB rows included (each under a 10 s timeout).
 */
class NumberReadStepsTest {
  private static final int MIB = 1 << 20;

  /** {@code [1.} and {@code n - 2} zeros and {@code ]}: n + 2 bytes, whose one element is a token of n bytes equal to 1. */
  static byte[] document(int n) {
    return ("[" + token(n) + "]").getBytes(US_ASCII);
  }

  /** The element of {@link #document(int)}: {@code 1.} and {@code n - 2} zeros. */
  static String token(int n) {
    return "1." + "0".repeat(n - 2);
  }

  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          # today 2, 2 and 1 025: the read alone
          .[0] + 1 => 1024    => 3    => 2
          .[0] + 1 => 1025    => 4    => 2
          .[0] + 1 => 1048576 => 2049 => 2
          # the divisor once, though today it is read for the zero test and again for the quotient
          1 / .[0] => 1024    => 3    => 1
          1 / .[0] => 1025    => 4    => 1
          1 / .[0] => 1048576 => 2049 => 1
          .[0] - 1 => 1024    => 3    => 0
          .[0] - 1 => 1025    => 4    => 0
          .[0] * 1 => 1024    => 3    => 1
          .[0] * 1 => 1025    => 4    => 1
          .[0] % 2 => 1024    => 3    => 1
          .[0] % 2 => 1025    => 4    => 1
          """)
  void anArithmeticOperandTicksOncePerStartedKiBOfItsToken(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          # 1 + 0 folds to a computed number, so the pair compares as doubles: only the view's token ticks (today 2, 2, 1 025)
          .[0] < (1 + 0) => 1024    => 3    => false
          .[0] < (1 + 0) => 1025    => 4    => false
          .[0] < (1 + 0) => 1048576 => 2049 => false
          """)
  void anOrderOnTheDoublePathTicksOnlyTheViewsToken(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          # the exact path: the view's token and the 1 KiB that the literal 1 starts (today 2, 2, 1 025: none)
          .[0] == 1 => 1024    => 4    => true
          .[0] == 1 => 1025    => 5    => true
          .[0] == 1 => 1048576 => 2050 => true
          """)
  void anExactEqualityTicksBothItsTokens(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          # two values, 1.0…0 and 2, one tick each; the start once, though today it is read at the start and again for
          # the next value (today 4, 4, 1 027)
          [range(.[0]; 3)] | .[1] => 1024    => 5    => 2
          [range(.[0]; 3)] | .[1] => 1025    => 6    => 2
          [range(.[0]; 3)] | .[1] => 1048576 => 2051 => 2
          """)
  void rangeTicksItsStartOnce(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          # today 3: the read and the comma of [5,6]
          .[0] as $n | [5,6] | .[$n]    => 1024 => 4 => 6
          .[0] as $n | [5,6] | .[$n]    => 1025 => 5 => 6
          # today 6: the read, the two commas of [5,6,7] and the slice's 2
          .[0] as $n | [5,6,7] | .[$n:] => 1024 => 7 => [6,7]
          .[0] as $n | [5,6,7] | .[$n:] => 1025 => 8 => [6,7]
          # today 3
          .[0] as $n | [5,6] | has($n)  => 1024 => 4 => true
          .[0] as $n | [5,6] | has($n)  => 1025 => 5 => true
          """)
  void anIndexSliceOrHasBoundTicksOncePerStartedKiBOfItsToken(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          # today 2
          .[0] | isinfinite => 1024 => 3 => false
          .[0] | isinfinite => 1025 => 4 => false
          """)
  void aNumberTestTicksOncePerStartedKiBOfItsToken(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  /**
   * {@code -x} and {@code length} copy the token and tick at the read's rate (today 2 for both). jq-ref prints
   * {@code -.[0]} as the token with a minus (the sign column {@code -}) and {@code .[0] | length} as the token itself
   * ({@code +}), 1 025 and 1 024 bytes at n = 1 024, 1 026 and 1 025 at n = 1 025.
   */
  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          -.[0]         => 1024 => 3 => -
          -.[0]         => 1025 => 4 => -
          .[0] | length => 1024 => 3 => +
          .[0] | length => 1025 => 4 => +
          """)
  void negationAndLengthTickOncePerStartedKiBOfTheToken(String filter, int n, long steps, char sign) {
    assertSteps(filter, document(n), steps, sign == '-' ? "-" + token(n) : token(n));
  }

  /**
   * No double is cached per token (ADR-0008 amendment for #97): {@code .[0] + .[0]} reads the same token as each of
   * its two operands, so it ticks its KiB twice; a cache would tick them once, 3 and 4. Today 2 and 2.
   */
  @ParameterizedTest(name = "{0} at n = {1}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          .[0] + .[0] => 1024 => 4 => 2
          .[0] + .[0] => 1025 => 6 => 2
          """)
  void eachReadOfTheSameTokenTicks(String filter, int n, long steps, String result) {
    assertSteps(filter, document(n), steps, result);
  }

  /**
   * A short literal (GLOSSARY: canonical, no exponent, at most 15 bytes) ticks nothing; one byte more is a non-short
   * view, whose token starts 1 KiB. Each document is under 1 KiB, one read tick. The 15-byte rows are guards that hold
   * today; the 16-byte rows tick 1 more for {@code +} and 2 more for {@code ==}, whose exact path also ticks the
   * literal 1 (today 1 for each row). jq-ref prints 1.0000000000000 and 1.00000000000000 as written, so both are
   * canonical.
   */
  @ParameterizedTest(name = "{1} on {0}: {2} steps")
  @CsvSource(
      delimiterString = "=>",
      textBlock =
          """
          [1.2345678901234]  => .[0] + 1  => 1 => 2.2345678901234
          [1.0000000000000]  => .[0] + 1  => 1 => 2
          [1.0000000000000]  => .[0] == 1 => 1 => true
          [1.00000000000000] => .[0] + 1  => 2 => 2
          [1.00000000000000] => .[0] == 1 => 3 => true
          """)
  void onlyANonShortViewTicks(String json, String filter, long steps, String result) {
    assertSteps(filter, json.getBytes(US_ASCII), steps, result);
  }

  /**
   * A guard that holds today: the host accessor {@code doubleValue()} charges no run (ADR-0008 amendment for #97, as
   * ADR-0044's host accessors). Three host reads of the 1 025-byte token between the read and the run leave the next
   * run of {@code .} its read's 2 ticks; a tick in {@code doubleValue()} would add 2 per read.
   */
  @Test
  void aHostReadOfTheDoubleChargesNoRun() {
    byte[] json = document(1025);
    JqEngine exact = steps(2);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    JqNumber element = (JqNumber) ((JqArray) root).get(0);
    for (int i = 0; i < 3; i++) {
      assertThat(element.doubleValue()).as("host read %d of the token's double", i + 1).isEqualTo(1.0);
    }
    Results results = new Results();
    assertThat(exact.compile(".").run(root, NONE, results, whole)).as("run's answer for . after three host reads, with 2 steps").isTrue();
    // jq-ref 1.8.2 prints the document unchanged under -c . (observed 2026-11-14).
    assertThat(results.values.stream().map(JqValue::toJson).toList()).as("results of . after three host reads").containsExactly(new String(json, US_ASCII));

    // Witness: the budget is live, the read's 2 ticks end a run of 1 step.
    JqEngine under = steps(1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    JqNumber cutElement = (JqNumber) ((JqArray) cutRoot).get(0);
    assertThat(cutElement.doubleValue()).as("host read of the token's double on the cut").isEqualTo(1.0);
    assertBudget(() -> under.compile(".").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, ". after a host read with 1 step");
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  /**
   * The read's and the token's rates together: for a token of L bytes, 16 &lt;= L &lt;= 64 KiB, {@code .[0] + 1} costs
   * ⌈(L + 2) / 1024⌉ + ⌈L / 1024⌉ steps, the document's started KiB (Workspace.read) and the token's. Each seed picks
   * the token's KiB t first and whether the document starts one KiB more (the two lengths L = 1024t - 1 and 1024t,
   * whose brackets cross into the next KiB), then L to fit, so the expected count is picked, not computed; half the
   * seeds land on that edge, which a uniform L would almost never meet. jq-ref 1.8.2 gives 2 for every L tried
   * (observed 2026-11-14 at 16, 1 023, 1 024, 1 025, 65 535 and 65 536).
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aSumCostsTheStartedKiBOfTheDocumentAndOfItsToken(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    int tokenKiB = random.nextInt(1, 65);
    boolean edge = random.nextBoolean();
    int readKiB = edge ? tokenKiB + 1 : tokenKiB;
    int length = edge ? tokenKiB * 1024 - random.nextInt(0, 2) : random.nextInt(Math.max(16, (tokenKiB - 1) * 1024 + 1), tokenKiB * 1024 - 1);
    assertThat(length).as("seed %d: the token's length", seed).isBetween(16, 65_536);
    String what = String.format("seed %d: a token of %d bytes (%d KiB read, %d KiB of token)", seed, length, readKiB, tokenKiB);
    assertSteps(".[0] + 1", document(length), readKiB + tokenKiB, "2", what);
  }

  /** {@link #assertSteps(String, byte[], long, String, String)}, described by the filter and the document's size. */
  private static void assertSteps(String filter, byte[] json, long steps, String text) {
    assertSteps(filter, json, steps, text, filter + " on " + json.length + " bytes");
  }

  /**
   * The adjacent pair: {@code filter} on {@code json}, read on the run's own Workspace, completes with {@code steps}
   * steps, giving {@code text}, and ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertSteps(String filter, byte[] json, long steps, String text, String what) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    Results all = new Results();
    assertThat(exact.compile(filter).run(root, NONE, all, whole)).as("run's answer for %s with %d steps", what, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of %s with %d steps", what, steps).containsExactly(text);
    JqEngine under = steps(steps - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(() -> under.compile(filter).run(cutRoot, NONE, new Results(), cut), Budget.STEPS, what + " with " + (steps - 1) + " steps");
  }
}
