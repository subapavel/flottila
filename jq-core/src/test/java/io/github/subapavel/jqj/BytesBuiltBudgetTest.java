package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes-built budget at the public API (ADR-0035, ADR-0017 amendment, #64). The builder charges before it
 * allocates: an array element 8 B, an object entry 16 B, and a fixed {@code CONTAINER} charge per built container,
 * 64 B, the largest empty container #64 measured with {@code -prof gc} (F-39, bench/RESULTS.md "Fixed charges of bytes
 * built", 2026-11-13). So a known value has a known charge: {@code [.[]]} on [1,2,3] is 64 + 3 x 8 = 88 B and
 * {@code {a:.a}} 64 + 16 = 80 B, and each such pin is an adjacent pair, the run completes under N and ends under N - 1
 * with {@code JqTermination(BUDGET, BYTES_BUILT)}, exit code 5 and "Bytes-built budget exceeded". The other tests find a
 * construction's boundary, the least limit under which its run completes, by bisection over fresh engines (as
 * {@code StepBudgetTest.stepBudgetCutsExactly} cuts at its exact count), and compare the boundaries of constructions
 * whose charges differ by a known number of elements or entries. Every input is a document, read on another engine's
 * Workspace, so its views are not built values and the read charges nothing. The expected results are jq-ref 1.8.2's,
 * observed 2026-11-12 ({@code [.[]]}, {@code [[.[]]]}, {@code .[1:]} and {@code .[2:]} on [1,2,3]; the objects on
 * {"a":1,"b":2,"c":3}) and 2026-11-13 ({@code [.[]] | .[1:]} and {@code [.[]] | .[2:]} on [1,2,3] give [2,3] and [3];
 * {@code {(.k):.a}}, {@code {(.k):.a,(.l):.b}} and {@code {(.k):.a,(.l):.b,(.m):.c}} on
 * {"k":"x","l":"y","m":"z","a":1,"b":2,"c":3} give {"x":1}, {"x":1,"y":2} and {"x":1,"y":2,"z":3}).
 */
class BytesBuiltBudgetTest {
  /** Far above any construction here: every boundary search starts from a limit that completes. */
  private static final long CEILING = 1 << 20;

  /** The engine whose Workspaces read the inputs; a run never reads, so no run charges or ticks for them. */
  private static final JqEngine READER = JqEngine.builder().build();

  @Test
  void aConstructionOverTheLimitEndsWithBytesBuilt() {
    JqValue input = document("[1,2,3]");
    long boundary = boundary("[.[]]", input);
    assertThat(boundary).as("boundary of [.[]] on [1,2,3]: CONTAINER 64 + 3 x 8").isEqualTo(88);
    assertThat(results("[.[]]", input, boundary)).as("results of [.[]] at its boundary").containsExactly("[1,2,3]");

    JqEngine under = bytesBuilt(boundary - 1);
    Workspace workspace = under.newWorkspace();
    Results none = new Results();
    assertBudget(() -> under.compile("[.[]]").run(input, NONE, none, workspace), Budget.BYTES_BUILT, "[.[]] on [1,2,3] one byte under its boundary");
    assertThat(none.values).as("results of [.[]] one byte under its boundary").isEmpty();
    // The Workspace is free after the termination (ADR-0030): the next run on it with room to spare completes.
    assertThat(bytesBuilt(boundary).compile("[.[]]").run(input, NONE, new Results(), workspace)).as("a later run on the same Workspace").isTrue();
  }

  /**
   * The charge of a known value (#64's acceptance): CONTAINER is 64 B (bench/RESULTS.md, 2026-11-13), so the collected
   * {@code [.[]]} on [1,2,3] costs 64 + 3 x 8 = 88 B and the shape site's {@code {a:.a}} 64 + 16 = 80 B; each completes
   * under its charge and ends one byte under it.
   */
  @Test
  void theMeasuredChargesPinAKnownValue() {
    assertCut("[.[]]", document("[1,2,3]"), 88, "[1,2,3]");
    assertCut("{a:.a}", document("{\"a\":1,\"b\":2,\"c\":3}"), 80, "{\"a\":1}");
  }

  /**
   * A growable object, a site with a computed key, charges a CONTAINER and an entry per key and value pair it is given
   * (ADR-0035, BuildObject): one pair 64 + 16 = 80 B, as the shape site of one key, two pairs 96 B and three 112 B.
   */
  @Test
  void aGrowableObjectChargesAContainerAndAnEntryPerPair() {
    JqValue keys = document("{\"k\":\"x\",\"l\":\"y\",\"m\":\"z\",\"a\":1,\"b\":2,\"c\":3}");
    assertCut("{(.k):.a}", keys, 80, "{\"x\":1}");
    assertCut("{(.k):.a,(.l):.b}", keys, 96, "{\"x\":1,\"y\":2}");
    assertCut("{(.k):.a,(.l):.b,(.m):.c}", keys, 112, "{\"x\":1,\"y\":2,\"z\":3}");
  }

  /**
   * A slice of a built array copies its elements and charges CONTAINER + 8 x its length before it allocates (ADR-0035,
   * BuiltArray.slice): after the collector's 88 B, {@code .[1:]} on [1,2,3] adds 64 + 2 x 8 = 80 B and {@code .[2:]}
   * 64 + 8 = 72 B.
   */
  @Test
  void aSliceOfABuiltArrayChargesItsCopy() {
    JqValue three = document("[1,2,3]");
    assertCut("[.[]] | .[1:]", three, 168, "[2,3]");
    assertCut("[.[]] | .[2:]", three, 160, "[3]");
  }

  /**
   * A collector charges 8 B per element collected, not the capacity it grows to (ADR-0035): from 1 to 48 elements the
   * boundary rises by exactly 8 per element, across whatever growth points the collector has. A charge by capacity
   * moves in steps there; a charge without elements never moves.
   */
  @Test
  void aCollectorChargesEightBytesPerElement() {
    long one = boundary("[.[]]", document(ones(1)));
    for (int n = 2; n <= 48; n++) {
      assertThat(boundary("[.[]]", document(ones(n)))).as("boundary of [.[]] on %d ones minus that on one", n).isEqualTo(one + 8L * (n - 1));
    }
  }

  /**
   * An element costs 8 B whatever it holds: {@code [[.[]]]} builds the inner array (CONTAINER + 3 x 8) and an outer one
   * holding it (CONTAINER + 8), which is the boundary of {@code [.[]]} on three elements plus that on one.
   */
  @Test
  void anElementCostsEightBytesWhateverItHolds() {
    JqValue three = document("[1,2,3]");
    assertThat(results("[[.[]]]", three, CEILING)).as("results of [[.[]]] on [1,2,3]").containsExactly("[[1,2,3]]");
    assertThat(boundary("[[.[]]]", three))
        .as("boundary of [[.[]]] on [1,2,3]")
        .isEqualTo(boundary("[.[]]", three) + boundary("[.[]]", document("[1]")));
  }

  /**
   * An object entry costs 16 B, for a key and a value (ADR-0017), and a built object one CONTAINER: each pair adds 16,
   * and an object of one entry costs what an array of two elements does. Both are collected from the same document, so
   * neither charge depends on its producer: {@code [.a,.b]} and {@code [.[]]} on two elements meet at one boundary.
   */
  @Test
  void anObjectEntryChargesSixteenBytes() {
    JqValue abc = document("{\"a\":1,\"b\":2,\"c\":3}");
    assertThat(results("{a:.a,b:.b,c:.c}", abc, CEILING)).as("results of {a:.a,b:.b,c:.c}").containsExactly("{\"a\":1,\"b\":2,\"c\":3}");
    long one = boundary("{a:.a}", abc);
    assertThat(boundary("{a:.a,b:.b}", abc)).as("boundary of two entries").isEqualTo(one + 16);
    assertThat(boundary("{a:.a,b:.b,c:.c}", abc)).as("boundary of three entries").isEqualTo(one + 32);
    long twoElements = boundary("[.[]]", document("[1,1]"));
    assertThat(one).as("boundary of one entry against that of two collected elements").isEqualTo(twoElements);
    assertThat(boundary("[.a,.b]", abc)).as("boundary of [.a,.b] against [.[]] on two elements").isEqualTo(twoElements);
  }

  /**
   * A slice copies (ADR-0034) and charges CONTAINER + 8 x its length before it allocates (ADR-0035, C8):
   * {@code .[1:]} on [1,2,3] is two slots, {@code .[2:]} one, so each meets the collector of as many elements.
   */
  @Test
  void aSliceChargesItsCopy() {
    JqValue three = document("[1,2,3]");
    assertThat(results(".[1:]", three, CEILING)).as("results of .[1:] on [1,2,3]").containsExactly("[2,3]");
    assertThat(results(".[2:]", three, CEILING)).as("results of .[2:] on [1,2,3]").containsExactly("[3]");
    assertThat(boundary(".[1:]", three)).as("boundary of .[1:] on [1,2,3]").isEqualTo(boundary("[.[]]", document("[1,1]")));
    assertThat(boundary(".[2:]", three)).as("boundary of .[2:] on [1,2,3]").isEqualTo(boundary("[.[]]", document("[1]")));
  }

  /**
   * Bytes built count monotonically over one run (ADR-0017): two arrays need twice one array's boundary, and one byte
   * less ends the run after the first array was emitted.
   */
  @Test
  void bytesBuiltAccumulateOverARun() {
    JqValue three = document("[1,2,3]");
    long one = boundary("[.[]]", three);
    assertThat(boundary("[.[]], [.[]]", three)).as("boundary of two arrays").isEqualTo(2 * one);

    JqEngine under = bytesBuilt(2 * one - 1);
    Workspace workspace = under.newWorkspace();
    Results first = new Results();
    assertBudget(() -> under.compile("[.[]], [.[]]").run(three, NONE, first, workspace), Budget.BYTES_BUILT, "two arrays one byte under twice the boundary");
    assertThat(first.values.stream().map(JqValue::toJson).toList()).as("results before the termination").containsExactly("[1,2,3]");
  }

  /** Each run counts from zero (ADR-0030: a run begins its budgets): two runs at one array's boundary both complete. */
  @Test
  void eachRunCountsBytesBuiltFromZero() {
    JqValue three = document("[1,2,3]");
    long one = boundary("[.[]]", three);
    JqEngine engine = bytesBuilt(one);
    JqFilter filter = engine.compile("[.[]]");
    Workspace workspace = engine.newWorkspace();
    for (int run = 1; run <= 3; run++) {
      Results results = new Results();
      assertThat(filter.run(three, NONE, results, workspace)).as("run %d at the boundary on one Workspace", run).isTrue();
      assertThat(results.values.stream().map(JqValue::toJson).toList()).as("results of run %d", run).containsExactly("[1,2,3]");
    }
  }

  /**
   * The adjacent pair: {@code filter} on {@code input} completes under {@code limit} bytes built, giving {@code texts},
   * and one byte under it ends with {@code JqTermination(BUDGET, BYTES_BUILT)} before it emits anything.
   */
  private static void assertCut(String filter, JqValue input, long limit, String... texts) {
    assertThat(results(filter, input, limit)).as("results of %s under %d bytes built", filter, limit).containsExactly(texts);
    JqEngine under = bytesBuilt(limit - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(input, NONE, none, under.newWorkspace()), Budget.BYTES_BUILT, filter + " under " + (limit - 1) + " bytes built");
    assertThat(none.values).as("results of %s under %d bytes built", filter, limit - 1).isEmpty();
  }

  /**
   * The least limit under which {@code filter} on {@code input} completes, by bisection over fresh engines. A run that
   * ends otherwise than by BYTES_BUILT (a Stub, an error) fails the search with its own exception.
   */
  private static long boundary(String filter, JqValue input) {
    assertThat(completes(filter, input, CEILING)).as("%s completes under %d bytes built", filter, CEILING).isTrue();
    long lo = 0;
    long hi = CEILING;
    while (lo < hi) {
      long mid = (lo + hi) >>> 1;
      if (completes(filter, input, mid)) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  private static boolean completes(String filter, JqValue input, long limit) {
    JqEngine engine = bytesBuilt(limit);
    try {
      return engine.compile(filter).run(input, NONE, new Results(), engine.newWorkspace());
    } catch (JqTermination t) {
      assertThat(t.budget()).as("budget that ended %s under %d bytes built", filter, limit).isEqualTo(Budget.BYTES_BUILT);
      assertThat(t.exitCode()).as("exit code of %s under %d bytes built", filter, limit).isEqualTo(5);
      assertThat(t.getMessage()).as("message of %s under %d bytes built", filter, limit).isEqualTo("Bytes-built budget exceeded");
      return false;
    }
  }

  /** The compact text of each result of {@code filter} on {@code input} under {@code limit}. */
  private static List<String> results(String filter, JqValue input, long limit) {
    JqEngine engine = bytesBuilt(limit);
    Results results = new Results();
    assertThat(engine.compile(filter).run(input, NONE, results, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }

  /** The root of {@code json}, read on a Workspace of its own that reads nothing else, so the view stays valid. */
  private static JqValue document(String json) {
    return read(READER.newWorkspace(), json.getBytes(US_ASCII)).root();
  }

  /** {@code [1,1,…,1]} with {@code n} elements. */
  private static String ones(int n) {
    return "[" + "1,".repeat(n - 1) + "1]";
  }
}
