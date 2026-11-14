package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The tick sites #63 adds, at the public API (ADR-0008, F-32, ADR-0034, the #63 approval's decision 3): a comma charges
 * one step on entering its right branch; a Many loop ({@code .[]}) one step per element, before its emit; a walk of a
 * read object first proves its keys, one step per key entry (ADR-0044, #85), whichever walker asks; a tape walk
 * charges the entries it steps, once per walk: a field's key scan, {@code .[n]}, a view's length and a view's slice; a
 * built array's slice charges the elements it copies, a string's slice one step per started KiB. A run may take exactly
 * its budget of ticks and the next one ends it with {@code JqTermination(BUDGET, STEPS)} (ADR-0030): each site has an
 * adjacent pair, N steps complete and N - 1 end. A document under 1 KiB reads in one tick, one tick per started KiB
 * above (ADR-0015, StepBudgetTest); a run on a value no read made has no read tick. The sinks neither write nor tick.
 */
class NavigationBudgetTest {

  /** {@code 1, 1, …, 1} with {@code n} ones. */
  private static String ones(int n) {
    StringJoiner chain = new StringJoiner(", ");
    for (int i = 0; i < n; i++) chain.add("1");
    return chain.toString();
  }

  /** {@code [1,1,…,1]} with {@code n} elements. */
  private static String array(int n) {
    StringJoiner array = new StringJoiner(",", "[", "]");
    for (int i = 0; i < n; i++) array.add("1");
    return array.toString();
  }

  @Test
  void commaChainOfFortyEndsAsStepBudget() {
    // Spec #56 story 18 and #63's acceptance: a comma chain of 40 under a small step budget. The chain enters 39 right
    // branches; with 10 steps the 11th entry ends the run, after the 11 ones that came before it. jq-ref 1.8.2 gives the
    // 40 ones (comma/three and the chains of diff/comma.rec: a comma emits its left results, then its right ones).
    JqEngine ten = steps(10);
    Results results = new Results();
    assertBudget(() -> ten.compile(ones(40)).run(JqNull.NULL, NONE, results, ten.newWorkspace()), Budget.STEPS, "a chain of 40 with 10 steps");
    assertThat(results.values).as("results before the 11th right branch").hasSize(11);
  }

  @Test
  void commaChargesOneStepPerRightBranch() {
    // 40 ones enter 39 right branches: 39 steps complete, 38 end the run before the last one.
    JqEngine exact = steps(39);
    Results all = new Results();
    assertThat(exact.compile(ones(40)).run(JqNull.NULL, NONE, all, exact.newWorkspace())).as("run's answer for 40 ones with 39 steps").isTrue();
    assertThat(all.values).as("results of 40 ones with 39 steps").hasSize(40);
    assertThat(all.values.stream().map(JqValue::toJson).distinct().toList()).as("the texts of 40 ones").containsExactly("1");

    JqEngine under = steps(38);
    Results cut = new Results();
    assertBudget(() -> under.compile(ones(40)).run(JqNull.NULL, NONE, cut, under.newWorkspace()), Budget.STEPS, "40 ones with 38 steps");
    assertThat(cut.values).as("results of 40 ones with 38 steps").hasSize(39);

    // A chain of two: one right branch, one step; no step for a lone constant.
    JqEngine one = steps(1);
    Results two = new Results();
    assertThat(one.compile("1, 2").run(JqNull.NULL, NONE, two, one.newWorkspace())).as("run's answer for 1, 2 with 1 step").isTrue();
    assertThat(two.values).as("results of 1, 2").hasSize(2);
    JqEngine zero = steps(0);
    Results first = new Results();
    assertBudget(() -> zero.compile("1, 2").run(JqNull.NULL, NONE, first, zero.newWorkspace()), Budget.STEPS, "1, 2 with 0 steps");
    assertThat(first.values).as("results of 1, 2 with 0 steps: the left one only").hasSize(1);
  }

  @Test
  void iterateChargesOneStepPerElement() {
    // [1,…,1] with 20 elements: 41 bytes, one read tick, then one tick per element before its emit (ADR-0034).
    String twenty = array(20);
    JqEngine exact = steps(21);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, twenty).root();
    Results all = new Results();
    assertThat(exact.compile(".[]").run(root, NONE, all, whole)).as("run's answer for .[] over 20 elements with 21 steps").isTrue();
    assertThat(all.values).as("results of .[] over 20 elements").hasSize(20);

    JqEngine under = steps(20);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, twenty).root();
    Results some = new Results();
    assertBudget(() -> under.compile(".[]").run(cutRoot, NONE, some, cut), Budget.STEPS, ".[] over 20 elements with 20 steps");
    // The 20th element's tick comes before its emit.
    assertThat(some.values).as("results of .[] over 20 elements with 20 steps").hasSize(19);
  }

  /** {@code {"k0":0,…,"k19":19}}: 20 keys, under 1 KiB. */
  private static final String TWENTY_KEYS = twentyKeys();

  private static String twentyKeys() {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = 0; i < 20; i++) object.add("\"k" + i + "\":" + i);
    return object.toString();
  }

  @Test
  void fieldChargesForTheKeysItScans() {
    // Decision 3 of the #63 approval: a field's key scan charges tick(keys). The last value of a key wins (ADR-0002), so
    // the scan reads all 20 keys for the first key as for a missing one: 1 read tick and 20 scan ticks.
    for (String[] c : new String[][] {{".k0", "0"}, {".zz", "null"}}) {
      JqEngine exact = steps(21);
      Workspace whole = exact.newWorkspace();
      JqValue root = read(whole, TWENTY_KEYS).root();
      Results results = new Results();
      assertThat(exact.compile(c[0]).run(root, NONE, results, whole)).as("run's answer for %s over 20 keys with 21 steps", c[0]).isTrue();
      assertThat(results.values).as("results of %s over 20 keys", c[0]).hasSize(1);
      assertThat(results.values.getFirst().toJson()).as("the result of %s over 20 keys", c[0]).isEqualTo(c[1]);

      JqEngine under = steps(20);
      Workspace cut = under.newWorkspace();
      JqValue cutRoot = read(cut, TWENTY_KEYS).root();
      assertBudget(() -> under.compile(c[0]).run(cutRoot, NONE, new Results(), cut), Budget.STEPS, c[0] + " over 20 keys with 20 steps");
    }
  }

  @Test
  void indexChargesForTheEntriesItSteps() {
    // ADR-0034: .[n] charges the entries it steps. Past the end it steps all 20: 1 read tick and 20 walk ticks; jq-ref
    // gives null past the end (index/key-3).
    String twenty = array(20);
    JqEngine exact = steps(21);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, twenty).root();
    Results past = new Results();
    assertThat(exact.compile(".[20]").run(root, NONE, past, whole)).as("run's answer for .[20] over 20 elements with 21 steps").isTrue();
    assertThat(past.values).as("results of .[20] over 20 elements").containsExactly(JqNull.NULL);
    JqEngine under = steps(20);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, twenty).root();
    assertBudget(() -> under.compile(".[20]").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, ".[20] over 20 elements with 20 steps");

    // .[19] steps over the 19 elements before its own: 1 read tick and 19 walk ticks (ArrayView.get, one tick per element
    // stepped over).
    assertExactSteps(".[19]", twenty, 20, "1");
  }

  /**
   * The adjacent pair at the public API: {@code filter} on the document {@code json} completes with {@code steps} steps,
   * giving {@code texts}, and ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertExactSteps(String filter, String json, long steps, String... texts) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    Results all = new Results();
    boolean[] answer = new boolean[1];
    assertThatCode(() -> answer[0] = exact.compile(filter).run(root, NONE, all, whole)).as("run of %s with %s steps", filter, steps).doesNotThrowAnyException();
    assertThat(answer[0]).as("run's answer for %s with %s steps", filter, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of %s with %s steps", filter, steps).containsExactly(texts);
    JqEngine under = steps(steps - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(() -> under.compile(filter).run(cutRoot, NONE, new Results(), cut), Budget.STEPS, filter + " with " + (steps - 1) + " steps");
  }

  /** {@code [0,1,…,19]}, under 1 KiB. */
  private static final String COUNTING = counting();

  private static String counting() {
    StringJoiner array = new StringJoiner(",", "[", "]");
    for (int i = 0; i < 20; i++) array.add(Integer.toString(i));
    return array.toString();
  }

  @Test
  void aNegativeIndexChargesTheLengthOfAView() {
    // A negative index counts from the end, so a view's length is walked first: one tick per element (ArrayView.length),
    // then the walk to the element. The key comes from the document (a negative literal in the filter is a Stub):
    // [-1,1,…,19] read 1, .[0] steps 0, the length 20, the walk to index 19 steps 19: 40. jq-ref 1.8.2, observed
    // 2026-11-12: jq-ref -nc '[-1, range(1;20)] | .[.[0]]' gives 19.
    String array = COUNTING.replaceFirst("\\[0,", "[-1,");
    assertExactSteps(".[.[0]]", array, 40, "19");
  }

  @Test
  void aSliceOfAViewChargesTheLengthAndTheElementsUpToItsEnd() {
    // Slice.of walks the length (20), then ArrayView.slice charges its end, the walk to the start and the copy (5):
    // read 1 + 20 + 5 = 26. jq-ref 1.8.2, observed 2026-11-12: [range(20)] | .[2:5] gives [2,3,4].
    assertExactSteps(".[2:5]", COUNTING, 26, "[2,3,4]");
  }

  @Test
  void aSliceOfABuiltArrayChargesOneStepPerElementCopied() {
    // .[1:] on the view: 20 + 20; then .[1:] on the 19 elements it built: no walk for the length, and 18 copied
    // (BuiltArray.slice): read 1 + 40 + 18 = 59. jq-ref 1.8.2, observed 2026-11-12: [range(20)] | .[1:] | .[1:] gives
    // [2,…,19].
    StringJoiner rest = new StringJoiner(",", "[", "]");
    for (int i = 2; i < 20; i++) rest.add(Integer.toString(i));
    assertExactSteps(".[1:] | .[1:]", COUNTING, 59, rest.toString());
  }

  @Test
  void aSliceOfAStringChargesOneStepPerStartedKiB() {
    // Slice.string counts the code points in one pass over the bytes: one tick per started KiB of them, the writer's
    // rate. The document's read is one tick per started KiB too (ADR-0015): "a"×1024 is a 1026-byte document, 2 read
    // ticks, and 1 slice tick; "a"×1025 and "é"×513 (1026 bytes) take 2 slice ticks, so bytes count, not code points;
    // "abc" takes 1. jq-ref 1.8.2, observed 2026-11-12: "a" * 1024 | .[1:3] and "a" * 1025 | .[1:3] give "aa",
    // "é" * 513 | .[1:3] gives "éé".
    assertExactSteps(".[1:3]", "\"" + "a".repeat(1024) + "\"", 3, "\"aa\"");
    assertExactSteps(".[1:3]", "\"" + "a".repeat(1025) + "\"", 4, "\"aa\"");
    assertExactSteps(".[1:3]", "\"" + "é".repeat(513) + "\"", 4, "\"éé\"");
    assertExactSteps(".[1:3]", "\"abc\"", 2, "\"bc\"");
  }

  /** {@link #TWENTY_KEYS} with k0 repeated last: 20 keys in 21 key entries, k0's last value 20. */
  private static final String REPEATED = TWENTY_KEYS.substring(0, TWENTY_KEYS.length() - 1) + ",\"k0\":20}";

  /** {@code [o,o]} for {@link #TWENTY_KEYS} o, under 1 KiB. */
  private static final String TWO_OBJECTS = "[" + TWENTY_KEYS + "," + TWENTY_KEYS + "]";

  /** 0 to 19, the values of {@link #TWENTY_KEYS}; with {@code repeated}, k0's last value 20 in k0's first place. */
  private static String[] memberValues(boolean repeated) {
    String[] values = new String[20];
    for (int i = 0; i < 20; i++) values[i] = Integer.toString(i);
    if (repeated) values[0] = "20";
    return values;
  }

  /** The keys of {@link #TWENTY_KEYS} as a JSON array, {@code ["k0",…]}, in the order of {@code order}. */
  private static String keyArray(int... order) {
    StringJoiner keys = new StringJoiner(",", "[", "]");
    for (int i : order) keys.add("\"k" + i + "\"");
    return keys.toString();
  }

  private static final int[] SOURCE_ORDER = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};

  @Test
  void iterateOverAnObjectChargesItsProofAndEachMember() {
    // ADR-0044: .[] walks a read object through its key proof, one tick per key entry, and the loop charges one per
    // member before its emit (ADR-0034). 20 keys: 1 read tick, 20 proof ticks and 20 member ticks, so 41 complete and 40
    // end (n * n + n - 1 + 1 = 420 on 25ef36c, F-40). With k0 repeated the object has 21 key entries and 20 members:
    // 1 + 21 + 20 = 42. jq-ref 1.8.2, observed 2026-11-12: [.[]] over {"k0":0,…,"k19":19,"k0":20} gives [20,1,…,19].
    assertAll(() -> assertExactSteps(".[]", TWENTY_KEYS, 41, memberValues(false)), () -> assertExactSteps(".[]", REPEATED, 42, memberValues(true)));
  }

  @Test
  void lengthOfAnObjectChargesItsProof() {
    // ADR-0044: length is a walker; it counts the members its proof finds, one tick per key entry: 1 + 20 = 21 (210 on
    // 25ef36c), 1 + 21 = 22 with k0 repeated. jq-ref 1.8.2, observed 2026-11-13: length gives 20 on both.
    assertAll(() -> assertExactSteps("length", TWENTY_KEYS, 21, "20"), () -> assertExactSteps("length", REPEATED, 22, "20"));
  }

  @Test
  void keysUnsortedChargesItsProofAndEachKey() {
    // ADR-0044 and Keys (#72): the proof, one tick per key entry, then one per key collected: 1 + 20 + 20 = 41 (230 on
    // 25ef36c), 1 + 21 + 20 = 42 with k0 repeated. jq-ref 1.8.2, observed 2026-11-13: keys_unsorted gives
    // ["k0",…,"k19"] on both.
    assertAll(
        () -> assertExactSteps("keys_unsorted", TWENTY_KEYS, 41, keyArray(SOURCE_ORDER)),
        () -> assertExactSteps("keys_unsorted", REPEATED, 42, keyArray(SOURCE_ORDER)));
  }

  @Test
  void keysChargesItsProofEachKeyAndItsSort() {
    // keys_unsorted's ticks and one per compare of the sort for each started KiB of the shorter key (Keys, #72). jqj's
    // merge sort (F-48, #103) sorts these 20 keys in 56 compares (TimSort, before #103, in 52): 41 + 56 = 97, and
    // 42 + 56 = 98 with k0 repeated (the sort sees the same 20 keys). jq-ref 1.8.2, observed 2026-11-13: keys gives
    // ["k0","k1","k10",…,"k19","k2",…,"k9"] on both.
    String sorted = keyArray(0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 2, 3, 4, 5, 6, 7, 8, 9);
    assertAll(() -> assertExactSteps("keys", TWENTY_KEYS, 97, sorted), () -> assertExactSteps("keys", REPEATED, 98, sorted));
  }

  /** {@code [{"key":"k0","value":v0},…]} for {@link #memberValues}. */
  private static String entries(boolean repeated) {
    String[] values = memberValues(repeated);
    StringJoiner entries = new StringJoiner(",", "[", "]");
    for (int i = 0; i < 20; i++) entries.add("{\"key\":\"k" + i + "\",\"value\":" + values[i] + "}");
    return entries.toString();
  }

  @Test
  void toEntriesChargesItsProofAndEachEntry() {
    // ADR-0044: to_entries becomes a native walker (ADR-0042 amended): the proof, one tick per key entry, then one per
    // entry: 1 + 20 + 20 = 41 (650 on 25ef36c, the prelude's keys_unsorted and a field read per key), 1 + 21 + 20 = 42
    // with k0 repeated. jq-ref 1.8.2, observed 2026-11-13: to_entries gives [{"key":"k0","value":0},…] and, repeated,
    // k0's value 20 in the first place.
    assertAll(() -> assertExactSteps("to_entries", TWENTY_KEYS, 41, entries(false)), () -> assertExactSteps("to_entries", REPEATED, 42, entries(true)));
  }

  @Test
  void toEntriesOfAnArrayChargesEachEntry() {
    // Keys.entries: an array has no key proof, so to_entries charges one tick per entry: 1 read tick and 3. jq-ref
    // 1.8.2, observed 2026-11-13: [10,20,30] | to_entries gives [{"key":0,"value":10},{"key":1,"value":20},
    // {"key":2,"value":30}].
    assertExactSteps("to_entries", "[10,20,30]", 4, "[{\"key\":0,\"value\":10},{\"key\":1,\"value\":20},{\"key\":2,\"value\":30}]");
  }

  @Test
  void aMergeChargesItsReadSidesProofAndEachEntryCopied() {
    // ADR-0044: ObjectMerge walks both sides; the read side's proof is one tick per key entry, and the merge one per
    // entry copied (ArithmeticStepsTest): 1 + 20 + 20 = 41 whichever side the read object is on, for + as for *
    // (420 on 25ef36c). jq-ref 1.8.2, observed 2026-11-13: the four give the object unchanged.
    assertAll(
        Stream.of(". + {}", "{} + .", ". * {}", "{} * .").<Executable>map(filter -> () -> assertExactSteps(filter, TWENTY_KEYS, 41, TWENTY_KEYS)));
  }

  @Test
  void anOrderChargesBothProofsAndItsKeyLists() {
    // .[0] steps no entry and .[1] one (ArrayView.get). Order (ADR-0037) collects each side's key list through its proof:
    // 20 proof and 20 collect ticks per side, then 20 key pairs of one started KiB each, then 20 member pairs; the member
    // after a proved key is the next entry, which costs no scan. 1 + 1 + 80 + 20 + 20 = 122 (880 on 25ef36c). jq-ref
    // 1.8.2, observed 2026-11-13: [o,o] | .[0] < .[1] gives false.
    assertExactSteps(".[0] < .[1]", TWO_OBJECTS, 122, "false");
  }

  @Test
  void anEqualityChargesBothProofsAndEachMemberPair() {
    // ADR-0044 as amended by #95: Equality walks its left side through the proof (20) and takes the read right side
    // through its key index: the right side's proof (20), then a lookup of each left key in its table (20 keys, past the
    // pairwise 8), which charges none, and its size from the index; then one tick per member pair (20).
    // 1 + 1 + 20 + 20 + 20 = 62 (462 before #95, a field read per member scanning all 20 keys; 1 050 on 25ef36c).
    // jq-ref 1.8.2, observed 2026-11-13: [o,o] | .[0] == .[1] gives true.
    assertExactSteps(".[0] == .[1]", TWO_OBJECTS, 62, "true");
  }

  /** {@code {k0:0,…,k19:19}}: {@link #TWENTY_KEYS} as an object construction of constant keys. */
  private static final String TWENTY_KEYS_BUILT = TWENTY_KEYS.replace("\"", "");

  @Test
  void aBuiltLeftSideChargesTheReadRightSidesProofAndEachMemberPair() {
    // ADR-0044 as amended by #95 ("the left side may be read or built"): the built left side has no proof and its
    // construction charges none; the read right side's key index charges its proof (20) and no lookup in its table;
    // then one tick per member pair (20): 1 + 20 + 20 = 41. Before #95, measured: 441, a field read per member scanning
    // all 20 keys (n * m = 400) and the right side's size from a proof at the end (20). jq-ref 1.8.2, observed 2026-11-14
    // (.logs/95-jqref.log): {k0:0,…,k19:19} == . on {"k0":0,…,"k19":19} gives true.
    assertExactSteps(TWENTY_KEYS_BUILT + " == .", TWENTY_KEYS, 41, "true");
  }

  @Test
  void printingAnObjectChargesItsProof() {
    // ADR-0044: the Printer walks a read object through its proof, one tick per key entry, and charges no tick per
    // member; the writer charges one per started KiB of its drain (F-23). 1 + 20 + 1 = 22, and 1 + 21 + 1 = 23 with k0
    // repeated (2 on 25ef36c: the Printer's key scans charge nothing, F-40). jq-ref 1.8.2, observed 2026-11-13: -c .
    // prints the object unchanged and, repeated, {"k0":20,"k1":1,…,"k19":19}.
    assertAll(
        () -> assertExactPrinted(TWENTY_KEYS, 22, TWENTY_KEYS),
        () -> assertExactPrinted(REPEATED, 23, "{\"k0\":20" + TWENTY_KEYS.substring("{\"k0\":0".length())));
  }

  /**
   * The adjacent pair for the compact writer: {@code json} printed by {@code -c .} completes with {@code steps} steps,
   * printing {@code text} and an LF, and ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertExactPrinted(String json, long steps, String text) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    RecordingTarget target = new RecordingTarget();
    assertThat(exact.compile(".").run(root, NONE, writer(whole, target), whole)).as("run's answer printing %s with %s steps", json, steps).isTrue();
    assertThat(target.text()).as("output of %s with %s steps", json, steps).isEqualTo(text + "\n");
    JqEngine under = steps(steps - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(() -> under.compile(".").run(cutRoot, NONE, writer(cut, new RecordingTarget()), cut), Budget.STEPS, "printing " + json + " with " + (steps - 1) + " steps");
  }

  /** {@code json} as a JSON string: what {@code tojson} gives for a value whose compact text it is. */
  private static String quoted(String json) {
    return "\"" + json.replace("\"", "\\\"") + "\"";
  }

  @Test
  void printingInARunChargesTheProofToTheRun() {
    // ADR-0044 as amended at abb12ef: tojson, tostring, @text and interpolation print a read object through its proof on
    // the run's state, as -c . does: 1 read tick, 20 proof ticks and 1 for the started KiB of the text (TextFormat,
    // Interpolate), so 22 complete and 21 end (2 at 752f5a0: the proof charged a throwaway state). The colliding object
    // (RunBudgets.colliding) sorts: 17 entry ticks, 48 merge and 16 neighbour compares, so 1 + 81 + 1 = 83, the same
    // as -c . prints it. jq-ref 1.8.2, observed 2026-11-13: the four give the compact text as a string, tojson on the
    // colliding object too, and -c . prints that object unchanged.
    String colliding = RunBudgets.colliding();
    assertAll(
        Stream.concat(
            Stream.of("tojson", "tostring", "@text", "\"\\(.)\"").<Executable>map(filter -> () -> assertExactSteps(filter, TWENTY_KEYS, 22, quoted(TWENTY_KEYS))),
            Stream.<Executable>of(() -> assertExactSteps("tojson", colliding, 83, quoted(colliding)), () -> assertExactPrinted(colliding, 83, colliding))));
  }

  @Test
  void anErrorsDumpChargesTheProofToTheRun() {
    // ADR-0044 as amended at 878595e: an error text's dump of a read object (ErrorDump) prints it through its proof on
    // the run's state: 1 read tick and 20 proof ticks, so 21 complete and 20 end (1 before #85 round 3: the dump proved
    // the object on a throwaway state). One row per kind of caller: an operator's (Arithmetic, the object as either of
    // two operands and as the one), an index's key, a slice's either bound and an object key. jq-ref 1.8.2, observed
    // 2026-11-13: these texts.
    String head = "{\\\"k0\\\":0,\\\"k1\\\":1,\\\"k2\\\":2,\\\"k3...}";
    assertAll(
        () -> assertExactSteps("try (. + 1) catch .", TWENTY_KEYS, 21, "\"object (" + head + ") and number (1) cannot be added\""),
        () -> assertExactSteps("try (1 - .) catch .", TWENTY_KEYS, 21, "\"number (1) and object (" + head + ") cannot be subtracted\""),
        () -> assertExactSteps("try sort catch .", TWENTY_KEYS, 21, "\"object (" + head + ") cannot be sorted, as it is not an array\""),
        () -> assertExactSteps(". as $o | try (1 | .[$o]) catch .", TWENTY_KEYS, 21, "\"Cannot index number with object (" + head + ")\""),
        () -> assertExactSteps("try .[.:] catch .", TWENTY_KEYS, 21, "\"Cannot index object with object ({\\\"start\\\":{\\\"k0\\\":0,\\\"k1\\\":1,\\\"...})\""),
        () -> assertExactSteps("try .[:.] catch .", TWENTY_KEYS, 21, "\"Cannot index object with object ({\\\"start\\\":null,\\\"end\\\":{\\\"k0\\\"...})\""),
        () -> assertExactSteps("try {(.): 1} catch .", TWENTY_KEYS, 21, "\"Cannot use object (" + head + ") as object key\""));
  }

  @Test
  void anEmptyKeyCostsOneTickToEnterAndToCompare() {
    // ADR-0044: a key entry ticks max(1, started KiB), a sort compare max(1, started KiB of the shorter key); the empty
    // key has 0 KiB. {"":0}: 1 read tick and 1 entry tick. The colliding object: 1 + 17 entries + 64 compares, of which
    // the 6 with the empty key (one per merge level, and its neighbour) cost 1 each. jq-ref 1.8.2, observed 2026-11-13:
    // length gives 1 and 17.
    assertAll(() -> assertExactSteps("length", "{\"\":0}", 2, "1"), () -> assertExactSteps("length", RunBudgets.colliding(), 82, "17"));
  }

  @Test
  void iterateAfterAReadInTheSinkIsAStaleView() {
    // ADR-0026: an iteration that calls host code checks its document again after every call; the sink reads another
    // document into the same Workspace on its first call, so the next step must be STALE_VIEW, never an element of the
    // later document. The later calls touch no view, so only the iteration's own step can throw. jq-ref 1.8.2:
    // iterate/plain-array and nested-values give each element in order.
    for (String[] c : new String[][] {{"[1,2,3]", "[7,8,9]"}, {"{\"a\":1,\"b\":2,\"c\":3}", "{\"x\":7,\"y\":8,\"z\":9}"}}) {
      Workspace workspace = JqEngine.builder().build().newWorkspace();
      JqValue root = read(workspace, c[0]).root();
      List<String> calls = new ArrayList<>();
      List<JsonDoc> readInside = new ArrayList<>();
      JqSink reading =
          v -> {
            if (readInside.isEmpty()) {
              calls.add(v.toJson());
              readInside.add(read(workspace, c[1]));
            } else {
              calls.add("a later call");
            }
            return true;
          };
      assertStale(() -> JqEngine.builder().build().compile(".[]").run(root, NONE, reading, workspace), ".[] over " + c[0] + " after its sink read " + c[1]);
      assertThat(calls).as("sink calls for .[] over %s: the first element only", c[0]).containsExactly("1");
      // Witness: the document read inside the sink is current and reads.
      JqValue later = readInside.getFirst().root();
      if (later instanceof JqArray array) {
        assertThat(kind(JqNumber.class, array.get(1), "[7,8,9][1]").longValue()).as("[7,8,9][1]").isEqualTo(8L);
      } else {
        assertThat(kind(JqNumber.class, kind(JqObject.class, later, "{x,y,z}").get("y"), ".y").longValue()).as(".y of {x,y,z}").isEqualTo(8L);
      }
    }
  }
}
