package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A built object of more than 8 keys looks a key up through an index built on its first lookup (ADR-0003, #83); up to
 * 8 keys it compares them in order, one step per key compared (BuiltObjectFieldStepsTest). The index is a table of
 * slots, the least power of two at least twice the keys (32 for 9 to 16 keys); a key goes to the slot of its
 * {@code String.hashCode()} masked to the table, or the next free one after it. Building it charges one step per slot
 * it probes and {@code CONTAINER + ENTRY} per key, 64 + 16 B per key, to bytes built (ADR-0035), before it allocates;
 * each indexed lookup charges one step per slot it probes, up to the key's own or a free one (ADR-0008). A
 * one-letter key's hash is its code point, so the keys a to j take the slots 1 to 10 and probe one slot each, while
 * {@code A} (65) and {@code !} (33) start at slot 1 with {@code a} (97). Every step pin is an adjacent pair, N steps
 * complete and N - 1 end with {@code JqTermination(BUDGET, STEPS)}; every bytes pin one at N and N - 1 bytes built.
 * A comma charges one step on entering its right branch and the field {@code .k} on the one-key document one (ADR-0034).
 * The expected results are jq-ref 1.8.2's, observed 2026-11-13 and recorded in diff/wide.rec.
 */
class BuiltObjectIndexTest {
  private static final String EIGHT = "{a:1,b:2,c:3,d:4,e:5,f:6,g:7,h:8}";
  private static final String NINE = "{a:1,b:2,c:3,d:4,e:5,f:6,g:7,h:8,i:9}";
  private static final String TEN = "{a:1,b:2,c:3,d:4,e:5,f:6,g:7,h:8,i:9,j:10}";

  /** Nine keys, the last of which, {@code A}, collides with {@code a} and probes the slots 1 to 9. */
  private static final String COLLIDING = "{a:1,b:2,c:3,d:4,e:5,f:6,g:7,h:8,A:9}";

  /** Nine distinct keys from ten pairs: the computed key {@code a} comes twice and keeps its first place. */
  private static final String REPEATED = "{(.k):1,b:2,c:3,d:4,e:5,f:6,g:7,h:8,i:9,(.k):10}";

  /** Eight distinct keys from nine pairs. */
  private static final String REPEATED_EIGHT = "{(.k):1,b:2,c:3,d:4,e:5,f:6,g:7,(.k):8,h:9}";

  /** The engine whose Workspaces read the inputs; a run never reads, so no run charges or ticks for them. */
  private static final JqEngine READER = JqEngine.builder().build();

  @Test
  void eightKeysAreComparedInOrder() {
    assertExactSteps(EIGHT + " | .a", JqNull.NULL, 1, "1");
    assertExactSteps(EIGHT + " | .h", JqNull.NULL, 8, "8");
    assertExactSteps(EIGHT + " | .z", JqNull.NULL, 8, "null");
  }

  @Test
  void theFirstLookupPastEightKeysBuildsTheIndex() {
    // Nine keys: 9 steps to place them, then 1 to probe; ten keys: 10 and 1.
    assertExactSteps(NINE + " | .a", JqNull.NULL, 10, "1");
    assertExactSteps(NINE + " | .i", JqNull.NULL, 10, "9");
    assertExactSteps(NINE + " | .z", JqNull.NULL, 10, "null");
    assertExactSteps(TEN + " | .j", JqNull.NULL, 11, "10");
  }

  @Test
  void laterLookupsReuseTheIndex() {
    // 9 to build, then 1 per lookup and 1 per comma.
    assertExactSteps(NINE + " | .a, .b", JqNull.NULL, 12, "1", "2");
    assertExactSteps(NINE + " | .a, .b, .i", JqNull.NULL, 14, "1", "2", "9");
  }

  @Test
  void anIndexedLookupChargesEverySlotItProbes() {
    // Placing a to h probes 8 slots and A 9 (slots 1 to 9): 17 to build.
    assertExactSteps(COLLIDING + " | .a", JqNull.NULL, 18, "1");
    assertExactSteps(COLLIDING + " | .A", JqNull.NULL, 26, "9");
    // ! starts at slot 1 too and probes the slots 1 to 10, the last one free.
    assertExactSteps(COLLIDING + " | .\"!\"", JqNull.NULL, 27, "null");
  }

  @Test
  void sixteenKeysShareThirtyTwoSlots() {
    // a to o take the slots 1 to 15 and A, at slot 1 of 32, the first free one, 16: 15 + 16 to build, 16 to probe.
    // In 64 slots A would have slot 1 to itself.
    String sixteen = "{a:1,b:2,c:3,d:4,e:5,f:6,g:7,h:8,i:9,j:10,k:11,l:12,m:13,n:14,o:15,A:16}";
    assertExactSteps(sixteen + " | .A", JqNull.NULL, 47, "16");
    assertExactSteps(sixteen + " | .o", JqNull.NULL, 32, "15");
  }

  @Test
  void aGrowableObjectIndexesItsDistinctKeys() {
    JqValue input = document("{\"k\":\"a\"}");
    // .k twice, 9 to place the 9 distinct keys and 1 to probe.
    assertExactSteps(REPEATED + " | .a", input, 12, "10");
    assertExactSteps(REPEATED + " | .i", input, 12, "9");
    // Nine pairs of eight distinct keys stay linear: .k twice and the 8 keys compared.
    assertExactSteps(REPEATED_EIGHT + " | .h", input, 10, "9");
    assertExactSteps(REPEATED_EIGHT + " | .a", input, 3, "8");
  }

  @Test
  void anEscapedDocumentKeyIsIndexedByItsDecodedText() {
    JqValue input = document("{\"k\":\"\\u0061\\u00e9\"}");
    String wide = "{(.k):1,b:2,c:3,d:4,e:5,f:6,g:7,h:8,i:9}";
    assertThat(results(wide + " | .[\"aé\"], .a", input, Limits.UNLIMITED)).as("lookups on a key escaped in its document").containsExactly("1", "null");
  }

  @Test
  void theIndexChargesAContainerAndAnEntryPerKey() {
    // The object: 64 + 16 per pair; the index: 64 + 16 per distinct key, on the first lookup only.
    assertCut(EIGHT + " | .a", JqNull.NULL, 192, "1");
    assertCut(NINE, JqNull.NULL, 208, "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":9}");
    assertCut(NINE + " | .[]", JqNull.NULL, 208, "1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertCut(NINE + " | .a", JqNull.NULL, 416, "1");
    assertCut(NINE + " | .a, .b", JqNull.NULL, 416, "1", "2");
    assertCut(TEN + " | .a", JqNull.NULL, 448, "1");
    assertCut(REPEATED + " | .a", document("{\"k\":\"a\"}"), 432, "10");
  }

  @Test
  void aHostLooksUpAWideObjectThroughItsIndex() {
    JqObject fresh = (JqObject) single(NINE);
    assertThat(number(fresh.get("i"))).as("host get i of a fresh object").isEqualTo(9L);
    assertThat(number(fresh.get("a"))).as("host get a once indexed").isEqualTo(1L);
    assertThat(fresh.get("z")).as("host get of a missing key").isNull();
    JqObject colliding = (JqObject) single(COLLIDING);
    assertThat(number(colliding.get("A"))).as("host get A of the colliding keys").isEqualTo(9L);
    assertThat(colliding.get("!")).as("host get of a missing colliding key").isNull();

    // The run's lookup builds the index after the object is emitted; the host's lookups then use it.
    Results results = new Results();
    JqEngine engine = JqEngine.builder().build();
    assertThat(engine.compile(NINE + " | ., .h").run(JqNull.NULL, NONE, results, engine.newWorkspace())).as("run's answer").isTrue();
    JqObject indexed = (JqObject) results.values.getFirst();
    assertThat(number(indexed.get("b"))).as("host get b of an object a run indexed").isEqualTo(2L);
    assertThat(indexed.get("A")).as("host get of a missing key of an object a run indexed").isNull();
  }

  /**
   * The adjacent pair: {@code filter} on {@code input} completes with {@code steps} steps, giving {@code texts}, and
   * ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}, before it emits anything.
   */
  private static void assertExactSteps(String filter, JqValue input, long steps, String... texts) {
    JqEngine exact = steps(steps);
    Results all = new Results();
    assertThat(exact.compile(filter).run(input, NONE, all, exact.newWorkspace())).as("run's answer for %s with %s steps", filter, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of %s with %s steps", filter, steps).containsExactly(texts);
    JqEngine under = steps(steps - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(input, NONE, none, under.newWorkspace()), Budget.STEPS, filter + " with " + (steps - 1) + " steps");
    assertThat(none.values.size()).as("results of %s with %s steps", filter, steps - 1).isLessThan(texts.length);
  }

  /**
   * The adjacent pair: {@code filter} on {@code input} completes under {@code limit} bytes built, giving {@code texts},
   * and one byte under it ends with {@code JqTermination(BUDGET, BYTES_BUILT)}.
   */
  private static void assertCut(String filter, JqValue input, long limit, String... texts) {
    assertThat(results(filter, input, limit)).as("results of %s under %d bytes built", filter, limit).containsExactly(texts);
    JqEngine under = bytesBuilt(limit - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(input, NONE, none, under.newWorkspace()), Budget.BYTES_BUILT, filter + " under " + (limit - 1) + " bytes built");
    assertThat(none.values.size()).as("results of %s under %d bytes built", filter, limit - 1).isLessThan(texts.length);
  }

  /** The compact text of each result of {@code filter} on {@code input} under {@code limit} bytes built. */
  private static List<String> results(String filter, JqValue input, long limit) {
    JqEngine engine = bytesBuilt(limit);
    Results results = new Results();
    assertThat(engine.compile(filter).run(input, NONE, results, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /** The one result of {@code filter} on null. */
  private static JqValue single(String filter) {
    Results results = new Results();
    JqEngine engine = JqEngine.builder().build();
    assertThat(engine.compile(filter).run(JqNull.NULL, NONE, results, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    assertThat(results.values).as("results of %s", filter).hasSize(1);
    return results.values.getFirst();
  }

  private static long number(JqValue value) {
    assertThat(value).as("a number").isInstanceOf(JqNumber.class);
    return ((JqNumber) value).longValue();
  }

  /** The root of {@code json}, read on a Workspace of its own that reads nothing else, so the view stays valid. */
  private static JqValue document(String json) {
    return read(READER.newWorkspace(), json.getBytes(UTF_8)).root();
  }
}
