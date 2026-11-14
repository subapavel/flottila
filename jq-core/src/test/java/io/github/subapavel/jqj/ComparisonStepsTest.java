package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ticks of a comparison (ADR-0037): one per member pair compared (an array's elements, an object's values; GLOSSARY,
 * {@code Members}), one per key collected per side, one per started KiB of the shorter string, the member walks' own
 * (none for a built value or an array view), none for the sort of an object's keys, and none for If and Alt, so none
 * for {@code and}, {@code or}, {@code not} and {@code //}. A key pair of two key lists is a string pair, not a member
 * pair: it charges its KiB only (the test-author's reading of ADR-0037, an open question of the #66 red). Two numbers on
 * the short path and two values of different kinds charge nothing. Each count is an adjacent pair: N steps complete and
 * N - 1 end with {@code JqTermination(BUDGET, STEPS)} (ADR-0030). Constants and a construction charge no step, a comma
 * one per right branch, and a run on a value no read made has no read tick (NavigationBudgetTest,
 * BuiltObjectFieldStepsTest). A comparison builds no value and charges no bytes built (ADR-0035, ADR-0037, design
 * approval O1). Every expected result is jq-ref 1.8.2's, observed 2026-11-13 on the same filter and input.
 */
class ComparisonStepsTest {

  @Test
  void numbersAndDifferentKindsChargeNothing() {
    // The comma's right branch is the only tick: [true,true].
    assertExactSteps("[1 < 2, null < false]", JqNull.NULL, 1, "[true,true]");
  }

  @Test
  void anArrayChargesOneStepPerElementPairCompared() {
    assertExactSteps("[1] < [2]", JqNull.NULL, 1, "true");
    assertExactSteps("[[1]] < [[2]]", JqNull.NULL, 2, "true");
    // The inner empty array has no element pair: one step for the outer pair.
    assertExactSteps("[[]] < [[1]]", JqNull.NULL, 1, "true");
    assertExactSteps("[[[[1]]]] < [[[[1]]]]", JqNull.NULL, 4, "false");
  }

  @Test
  void anArrayViewChargesItsPairsAndNoWalk() {
    // Twenty ones read in one tick (under 1 KiB), then twenty element pairs; the array view's walk steps without a scan.
    String twenty = "[" + "1,".repeat(19) + "1]";
    assertExactDocumentSteps(". < .", twenty, 21, "false");
  }

  @Test
  void aStringPairChargesOneStepPerStartedKiBOfTheShorter() {
    // Each pair shares its whole shorter string as a prefix, so every byte of it is compared.
    assertExactSteps(quoted("a".repeat(1024)) + " < " + quoted("a".repeat(1024)), JqNull.NULL, 1, "false");
    assertExactSteps(quoted("a".repeat(1025)) + " < " + quoted("a".repeat(1025)), JqNull.NULL, 2, "false");
    assertExactSteps(quoted("a".repeat(1025)) + " < " + quoted("a".repeat(2049)), JqNull.NULL, 2, "true");
    assertExactSteps(quoted("a".repeat(2049)) + " < " + quoted("a".repeat(1025)), JqNull.NULL, 2, "false");
    // Bytes, not code points: 513 é are 1026 bytes.
    assertExactSteps(quoted("é".repeat(513)) + " < " + quoted("é".repeat(513)), JqNull.NULL, 2, "false");
    // An empty string starts no KiB: the element pair alone.
    assertExactSteps("[\"\"] < [\"a\"]", JqNull.NULL, 1, "true");
    assertExactSteps("[\"a\"] < [\"b\"]", JqNull.NULL, 2, "true");
  }

  @Test
  void anObjectChargesOneStepPerKeyCollectedPerSide() {
    // The key lists differ in length before any key pair: the collected keys alone.
    assertExactSteps("{} < {\"a\":1}", JqNull.NULL, 1, "true");
    assertExactSteps("{\"a\":1,\"b\":2} < {}", JqNull.NULL, 2, "false");
  }

  @Test
  void anObjectChargesItsKeysTheirKiBAndItsValuePairs() {
    // 2 keys collected, the key pair "a" "a" one KiB, the value pair 1 2.
    assertExactSteps("{\"a\":1} < {\"a\":2}", JqNull.NULL, 4, "true");
    // 4 keys collected, two key pairs of one KiB each, then the value pair at "a" decides.
    assertExactSteps("{\"b\":1,\"a\":2} < {\"a\":1,\"b\":2}", JqNull.NULL, 7, "false");
  }

  @Test
  void theSortOfAnObjectsKeysChargesNothing() {
    // The same keys in the opposite and in sorted insertion order cost the same: 16 keys collected, 8 key pairs, and the
    // value pair at "a".
    String sorted = "{\"a\":1,\"b\":1,\"c\":1,\"d\":1,\"e\":1,\"f\":1,\"g\":1,\"h\":1}";
    assertExactSteps("{\"h\":1,\"g\":1,\"f\":1,\"e\":1,\"d\":1,\"c\":1,\"b\":1,\"a\":2} < " + sorted, JqNull.NULL, 25, "false");
    assertExactSteps("{\"a\":2,\"b\":1,\"c\":1,\"d\":1,\"e\":1,\"f\":1,\"g\":1,\"h\":1} < " + sorted, JqNull.NULL, 25, "false");
    assertExactSteps("{\"a\":2,\"b\":1} < {\"a\":1,\"b\":2}", JqNull.NULL, 7, "false");
  }

  @Test
  void ifAndAltChargeNothing() {
    // The two commas are the only ticks.
    assertExactSteps("[true and (false or true), (null | not), null // 1]", JqNull.NULL, 2, "[true,true,1]");
    assertExactSteps("[(null, false) // 3]", JqNull.NULL, 1, "[3]");
    // The left comma, and the right one once: for true under and, for false under or.
    assertExactSteps("[(true, false) and (true, false)]", JqNull.NULL, 2, "[true,false,false]");
    assertExactSteps("[(true, false) or (true, false)]", JqNull.NULL, 2, "[true,true,false]");
  }

  @Test
  void aComparisonOfViewsBuildsNothing() {
    // No bytes built at all: the sorted key lists are scratch (O1). Witness: [.] under the same limit ends the run.
    String json = "{\"b\":[1,\"x\"],\"a\":{\"c\":\"y\"}}";
    JqEngine none = bytesBuilt(0);
    Workspace workspace = none.newWorkspace();
    JqValue root = read(workspace, json).root();
    Results results = new Results();
    assertThat(none.compile(". < .").run(root, NONE, results, workspace)).as("run's answer for . < . with no bytes built").isTrue();
    assertThat(texts(results)).as("results of . < . with no bytes built").containsExactly("false");

    Workspace cut = none.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(() -> none.compile("[.]").run(cutRoot, NONE, new Results(), cut), Budget.BYTES_BUILT, "[.] with no bytes built");
  }

  @Test
  void aComparisonOfBuiltObjectsChargesOnlyTheirConstruction() {
    // The two objects cost what building and emitting them does: jq-ref gives false, and {"b":2,"a":1} {"a":1,"b":2}.
    String json = "{\"a\":1,\"b\":2}";
    long compared = builtBoundary("{b:.b,a:.a} < {a:.a,b:.b}", json, "false");
    long built = builtBoundary("{b:.b,a:.a}, {a:.a,b:.b}", json, "{\"b\":2,\"a\":1}", "{\"a\":1,\"b\":2}");
    assertThat(compared).as("bytes built by the comparison against those of its operands").isEqualTo(built);
  }

  private static String quoted(String text) {
    return "\"" + text + "\"";
  }

  private static List<String> texts(Results results) {
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /**
   * The adjacent pair: {@code filter} on {@code input}, a value no read made, completes with {@code steps} steps, giving
   * {@code texts}, and ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertExactSteps(String filter, JqValue input, long steps, String... texts) {
    String what = filter.length() > 60 ? filter.substring(0, 60) + "…" : filter;
    JqEngine exact = steps(steps);
    Results all = new Results();
    assertThat(exact.compile(filter).run(input, NONE, all, exact.newWorkspace())).as("run's answer for %s with %s steps", what, steps).isTrue();
    assertThat(texts(all)).as("results of %s with %s steps", what, steps).containsExactly(texts);
    JqEngine under = steps(steps - 1);
    assertBudget(() -> under.compile(filter).run(input, NONE, new Results(), under.newWorkspace()), Budget.STEPS, what + " with " + (steps - 1) + " steps");
  }

  /** {@link #assertExactSteps} on the document {@code json}, read into each run's own Workspace. */
  private static void assertExactDocumentSteps(String filter, String json, long steps, String... texts) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    Results all = new Results();
    assertThat(exact.compile(filter).run(root, NONE, all, whole)).as("run's answer for %s with %s steps", filter, steps).isTrue();
    assertThat(texts(all)).as("results of %s with %s steps", filter, steps).containsExactly(texts);
    JqEngine under = steps(steps - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(() -> under.compile(filter).run(cutRoot, NONE, new Results(), cut), Budget.STEPS, filter + " with " + (steps - 1) + " steps");
  }

  /**
   * The least bytes-built limit under which {@code filter} on the document {@code json} completes with {@code texts}, by
   * bisection; one byte less must end with {@code JqTermination(BUDGET, BYTES_BUILT)}.
   */
  private static long builtBoundary(String filter, String json, String... texts) {
    long lo = 0;
    long hi = 1 << 20;
    assertThat(builtRun(filter, json, hi)).as("results of %s under %d bytes built", filter, hi).containsExactly(texts);
    while (lo < hi) {
      long mid = (lo + hi) >>> 1;
      if (builtRun(filter, json, mid) != null) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  /** The results of {@code filter} on {@code json} under {@code limit} bytes built, or null when BYTES_BUILT ends it. */
  private static List<String> builtRun(String filter, String json, long limit) {
    JqEngine engine = bytesBuilt(limit);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json).root();
    Results results = new Results();
    try {
      engine.compile(filter).run(root, NONE, results, workspace);
    } catch (JqTermination t) {
      assertThat(t.budget()).as("budget that ended %s under %d bytes built", filter, limit).isEqualTo(Budget.BYTES_BUILT);
      return null;
    }
    return texts(results);
  }
}
