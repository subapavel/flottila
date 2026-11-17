package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.bytesBuilt;
import static dev.rail8.jq.RunBudgets.steps;
import static dev.rail8.jq.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The ticks of a comparison (ADR-0037): one per member pair compared (an array's elements, an object's values; GLOSSARY,
 * {@code Members}), one per key collected per side, one per started KiB of the shorter string, the member walks' own
 * (none for a built value or an array view), one per compare of jqj's merge sort of each side's key list, max(1, started
 * KiB of the shorter key), as {@code keys} (F-53, ADR-0037 as amended by #104), and none for If and Alt, so none for
 * {@code and}, {@code or}, {@code not} and {@code //}. A key pair of two key lists is a string pair, not a member pair:
 * it charges its KiB only (the test-author's reading of ADR-0037, an open question of the #66 red). Two numbers on the
 * short path and two values of different kinds charge nothing. Each count is an adjacent pair: N steps complete and N -
 * 1 end with {@code JqTermination(BUDGET, STEPS)} (ADR-0030). Constants and a construction charge no step, a comma one
 * per right branch, and a run on a value no read made has no read tick (NavigationBudgetTest,
 * BuiltObjectFieldStepsTest). A built object's key list is collected in its insertion order. A comparison builds no
 * value and charges no bytes built, its sorted key lists included (ADR-0035, ADR-0037, design approval O1, F-53). Every
 * expected result is jq-ref 1.8.2's, observed 2026-11-13 on the same filter and input, and for #104's rows 2026-11-14
 * (.logs/104-jqref.log).
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
    // The key lists differ in length before any key pair. No key or one key: the collected keys alone, a list of one
    // needs no compare. Two keys: 2 collected and the one compare of their sort, "a" "b" at one KiB (F-53): 3 (2 before
    // F-53).
    assertAll(
        () -> assertExactSteps("{} < {\"a\":1}", JqNull.NULL, 1, "true"),
        () -> assertExactSteps("{\"a\":1,\"b\":2} < {}", JqNull.NULL, 3, "false"));
  }

  @Test
  void anObjectChargesItsKeysTheirKiBAndItsValuePairs() {
    // 2 keys collected, no sort compare for a list of one, the key pair "a" "a" one KiB, the value pair 1 2. Then 4 keys
    // collected, one sort compare per side (F-53), two key pairs of one KiB each, then the value pair at "a" decides: 9
    // (7 before F-53).
    assertAll(
        () -> assertExactSteps("{\"a\":1} < {\"a\":2}", JqNull.NULL, 4, "true"),
        () -> assertExactSteps("{\"b\":1,\"a\":2} < {\"a\":1,\"b\":2}", JqNull.NULL, 9, "false"));
  }

  @Test
  void theSortOfAnObjectsKeysChargesItsCompares() {
    // F-53: each side's 8 keys sort in 12 compares of jqj's merge sort, 4 per level of runs 1, 2 and 4, in the opposite
    // as in sorted insertion order, one tick each. 16 keys collected, 2 x 12 compares, 8 key pairs, and the value pair at
    // "a": 49 (25 before F-53). Two keys: 4 collected, one compare per side, 2 key pairs, the value pair at "a": 9 (7).
    String sorted = "{\"a\":1,\"b\":1,\"c\":1,\"d\":1,\"e\":1,\"f\":1,\"g\":1,\"h\":1}";
    assertAll(
        () -> assertExactSteps("{\"h\":1,\"g\":1,\"f\":1,\"e\":1,\"d\":1,\"c\":1,\"b\":1,\"a\":2} < " + sorted, JqNull.NULL, 49, "false"),
        () -> assertExactSteps("{\"a\":2,\"b\":1,\"c\":1,\"d\":1,\"e\":1,\"f\":1,\"g\":1,\"h\":1} < " + sorted, JqNull.NULL, 49, "false"),
        () -> assertExactSteps("{\"a\":2,\"b\":1} < {\"a\":1,\"b\":2}", JqNull.NULL, 9, "false"));
  }

  @Test
  void aKeySortChargesItsMergeSortsCompares() {
    // F-53: the count is the compares of jqj's bottom-up merge sort (internal.op.Sort), so it depends on the insertion
    // order. [c,b,a]: c b, then b a once the run [b,c] meets [a], 2 compares. [a,b,c]: a b, then a c and b c, 3 compares.
    // 6 keys collected, 3 key pairs, 3 value pairs, all equal: 12 + 2 x 2 = 16 and 12 + 2 x 3 = 18 (both 12 before F-53).
    assertAll(
        () -> assertExactSteps("{\"c\":1,\"b\":1,\"a\":1} < {\"c\":1,\"b\":1,\"a\":1}", JqNull.NULL, 16, "false"),
        () -> assertExactSteps("{\"a\":1,\"b\":1,\"c\":1} < {\"a\":1,\"b\":1,\"c\":1}", JqNull.NULL, 18, "false"));
  }

  @Test
  void aKeySortCompareChargesTheShorterKeysStartedKiB() {
    // F-53, as keys: the left list's one compare, A and B, charges the started KiB of the shorter of the two; the right
    // list has one key. 3 keys collected, the compare, and the key pair A "a" at one KiB (the shorter is 1 byte), which
    // decides: "a" is a prefix of A. Both 1024 bytes: 1 KiB, so 5; both 1025: 2 KiB, so 6 (all 4 before F-53). The
    // shorter, whichever side of the compare it is on: 1024 and 1025 bytes cost 1 KiB, not the longer's 2, so 5. Bytes,
    // not code points: 513 é and 513 ê are 1026 bytes each, 2 KiB, so 6.
    assertAll(
        () -> assertExactSteps(longKeys("a".repeat(1024), "b".repeat(1024)), JqNull.NULL, 5, "false"),
        () -> assertExactSteps(longKeys("a".repeat(1025), "b".repeat(1025)), JqNull.NULL, 6, "false"),
        () -> assertExactSteps(longKeys("a".repeat(1024), "b".repeat(1025)), JqNull.NULL, 5, "false"),
        () -> assertExactSteps(longKeys("a".repeat(1025), "b".repeat(1024)), JqNull.NULL, 5, "false"),
        () -> assertExactSteps(longKeys("é".repeat(513), "ê".repeat(513)), JqNull.NULL, 6, "false"));
  }

  @Test
  void aKeySortCompareChargesAtLeastOne() {
    // F-53, as keys: the compare of "" and "a" has a shorter key of 0 KiB and charges max(1, 0) = 1. 2 keys collected,
    // the compare, and the key lists differ in length: 3 (2 before F-53).
    assertExactSteps("{\"\":1,\"a\":1} < {}", JqNull.NULL, 3, "false");
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
    // No bytes built at all: the sorted key lists, two keys sorted by the merge sort, are scratch (O1, F-53). Witness: [.]
    // under the same limit ends the run.
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

  /** A run of 8 or more of one character, which a filter's description shows as that character and the run's length. */
  private static final Pattern RUN = Pattern.compile("(.)\\1{7,}");

  private static String quoted(String text) {
    return "\"" + text + "\"";
  }

  /** {@code {A:1,B:2} < {"a":1}}: two keys on the left, in this insertion order, one on the right. */
  private static String longKeys(String a, String b) {
    return "{" + quoted(a) + ":1," + quoted(b) + ":2} < {\"a\":1}";
  }

  private static List<String> texts(Results results) {
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /**
   * The adjacent pair: {@code filter} on {@code input}, a value no read made, completes with {@code steps} steps, giving
   * {@code texts}, and ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertExactSteps(String filter, JqValue input, long steps, String... texts) {
    String runs = RUN.matcher(filter).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "*" + m.group().length()));
    String what = runs.length() > 60 ? runs.substring(0, 60) + "…" : runs;
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
