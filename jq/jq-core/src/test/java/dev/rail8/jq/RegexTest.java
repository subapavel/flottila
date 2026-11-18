package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.assertCancelled;
import static dev.rail8.jq.RunBudgets.engine;
import static dev.rail8.jq.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The regex native under {@code test}, {@code match} and their kin: its ticks, its time limit per search, the deadline
 * and the interrupt during a search, and the order of its errors. A search ticks once, and once per started KiB from
 * where it starts to where its match ends, or to the input's end when nothing matches; a lookup ticks once per started
 * KiB of the pattern. The tick counts are differences between two filters that differ only there, each found as the
 * fewest steps its run completes with. Every input is a constant folded at parse time.
 */
class RegexTest {
  /** A pattern that backtracks for longer than any test runs on {@link #CATASTROPHIC}'s subject. */
  private static final String CATASTROPHIC = "(\"a\" * 40 + \"b\") | test(\"^(a+)+$\")";

  @Test
  void aSearchTicksOncePerStartedKibUpToTheMatchEnd() {
    long kib = fewestSteps("\"a\" * 2048 | test(\"a{1024}\")");
    assertThat(fewestSteps("\"a\" * 2048 | test(\"a{1023}\")")).as("a match ending at 1023 bytes").isEqualTo(kib);
    assertThat(fewestSteps("\"a\" * 2048 | test(\"a{1025}\")")).as("a match ending at 1025 bytes").isEqualTo(kib + 1);
  }

  @Test
  void aSearchWithoutAMatchTicksUpToTheInputsEnd() {
    long kib = fewestSteps("\"a\" * 1024 | test(\"b\")");
    assertThat(fewestSteps("\"a\" * 1025 | test(\"b\")")).as("no match in 1025 bytes").isEqualTo(kib + 1);
  }

  @Test
  void aGlobalMatchTicksOncePerSearch() {
    // Two matches of a byte and a search from the end that finds none: 2 + 2 + 1 ticks; one more match is 2 more. The
    // native is called directly, so no prelude step ticks per match.
    long two = fewestSteps("\"aa\" | _match_impl(\"a\"; \"g\"; false)");
    assertThat(fewestSteps("\"aaa\" | _match_impl(\"a\"; \"g\"; false)")).as("three matches").isEqualTo(two + 2);
    assertThat(fewestSteps("\"aaa\" | _match_impl(\"a\"; null; false)")).as("one match of three").isEqualTo(two - 2 - 1);
  }

  @Test
  void aLookupTicksOncePerStartedKibOfThePattern() {
    long kib = fewestSteps("\"b\" | test(\"b|\" + \"a\" * 1022)");
    assertThat(fewestSteps("\"b\" | test(\"b|\" + \"a\" * 1023)")).as("a pattern of 1025 bytes").isEqualTo(kib + 1);
  }

  @Test
  void theStepsDoNotDependOnWhatTheCacheHolds() {
    // The first run compiles the pattern, the later runs on the same Workspace find it cached: each completes with the
    // same steps, and none with one fewer.
    String filter = "\"abc\" | test(\"b+\")";
    long fewest = fewestSteps(filter);
    JqFilter enough = steps(fewest).compile(filter);
    JqFilter under = steps(fewest - 1).compile(filter);
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    for (int run = 0; run < 3; run++) {
      assertThat(enough.run(JqNull.NULL, NONE, new Results(), workspace)).as("run %s on one Workspace", run).isTrue();
      assertBudget(() -> under.run(JqNull.NULL, NONE, new Results(), workspace), Budget.STEPS, "run " + run + " with one step fewer");
    }
  }

  @Test
  void aSearchPastTheMatchTimeIsJqsRetryLimitError() {
    JqEngine engine = engine(matchTime(Duration.ofNanos(1), Limits.UNLIMITED_TIME));
    assertThat(results(engine, "try (" + CATASTROPHIC + ") catch .")).containsExactly("\"Regex failure: retry-limit-in-match over\"");
  }

  @Test
  void aSearchIntoTheDeadlineEndsTheRun() {
    JqEngine engine = engine(matchTime(Duration.ofSeconds(1), Duration.ofNanos(1)));
    assertBudget(
        () -> engine.compile("try (" + CATASTROPHIC + ") catch .").run(JqNull.NULL, NONE, new Results(), engine.newWorkspace()),
        Budget.DEADLINE,
        "a search past a 1 ns deadline");
  }

  @Test
  void anInterruptDuringASearchCancelsTheRunAndStaysSet() {
    JqEngine engine = engine(matchTime(Duration.ofSeconds(1), Limits.UNLIMITED_TIME));
    JqFilter filter = engine.compile(CATASTROPHIC);
    Workspace workspace = engine.newWorkspace();
    try {
      // The run ticks too little to refuel, so the search is the first to see the interrupt.
      Thread.currentThread().interrupt();
      assertCancelled(() -> filter.run(JqNull.NULL, NONE, new Results(), workspace), "a search on an interrupted thread");
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the cancelled search").isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void theInputIsCheckedFirstThenThePatternThenTheModifiers() {
    JqEngine engine = RunBudgets.unlimited();
    assertThat(results(engine, "1 | try _match_impl(2; 3; false) catch .")).containsExactly("\"number (1) cannot be matched, as it is not a string\"");
    assertThat(results(engine, "\"a\" | try _match_impl(2; 3; false) catch .")).containsExactly("\"number (2) is not a string\"");
    assertThat(results(engine, "\"a\" | try _match_impl(\"[\"; 3; false) catch .")).containsExactly("\"number (3) is not a string\"");
    assertThat(results(engine, "\"a\" | try _match_impl(\"[\"; \"q\"; false) catch .")).containsExactly("\"q is not a valid modifier string\"");
    assertThat(results(engine, "\"a\" | try _match_impl(\"[\"; null; false) catch .")).containsExactly("\"Regex failure: premature end of char-class\"");
  }

  @Test
  void theModifiersRunOutsideThePattern() {
    // jq evaluates a native's arguments last first, so the modifiers vary slowest.
    assertThat(results(RunBudgets.unlimited(), "[\"ab\" | test(\"a\", \"c\"; null, \"g\")]")).containsExactly("[true,false,true,false]");
  }

  /** {@link Limits#cli()} with the regex match time and the deadline given. */
  private static Limits matchTime(Duration regexMatchTime, Duration deadline) {
    Limits cli = Limits.cli();
    return new Limits(
        Limits.UNLIMITED, deadline, cli.nestingDepth(), cli.recursionDepth(), Limits.UNLIMITED, Limits.UNLIMITED, regexMatchTime, cli.stackBytes());
  }

  /** The compact text of each result of {@code filter} on null. */
  private static List<String> results(JqEngine engine, String filter) {
    Results all = new Results();
    assertThat(engine.compile(filter).run(JqNull.NULL, NONE, all, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  /** The fewest steps {@code filter} on null completes with. */
  private static long fewestSteps(String filter) {
    long low = 0;
    long high = 1;
    while (!completes(filter, high)) {
      low = high;
      high *= 2;
    }
    while (high - low > 1) {
      long mid = (low + high) >>> 1;
      if (completes(filter, mid)) {
        high = mid;
      } else {
        low = mid;
      }
    }
    return high;
  }

  /** Whether {@code filter} on null completes with {@code limit} steps. */
  private static boolean completes(String filter, long limit) {
    JqEngine engine = steps(limit);
    try {
      return engine.compile(filter).run(JqNull.NULL, NONE, new Results(), engine.newWorkspace());
    } catch (JqTermination t) {
      assertThat(t.budget()).as("the budget %s ends with", filter).isEqualTo(Budget.STEPS);
      return false;
    }
  }
}
