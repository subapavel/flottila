package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * The steps an operator's native loop charges (ADR-0008: every loop whose length depends on the data ticks per element
 * or per KiB), at the public API. Each rate is pinned by two counts: the step boundary (the least step budget under
 * which the run completes, found by bisection over fresh engines as StepBudgetTest cuts) of two inputs that differ only
 * in what the rate counts, so the sites the inputs share cancel out and the difference is the rate times the change.
 * The sites already built are known: a field on a document object scans every key (one tick each, ADR-0034), .[] ticks
 * once per element, a collect none; documents are read on another engine's Workspace, so a run has no read tick
 * (ADR-0015). The rates: a string concat one tick per started KiB of its result; an array difference one per pair
 * compared, stopping at the first equal element as jq's binop_minus does; a split one per piece; an object + or a merge
 * one per entry of the right operand, at every merge level. The #65 standards review added (ADR-0034, 5e4e90b): a
 * repeat one per started KiB of its result; an array + one per element copied; a difference one per left element; a
 * split one per started KiB searched; a merge one per left entry copied; an equality one per member pair and one per
 * key of the right object it counts. The re-review (94509b6) added: object + one per left entry copied too; a string
 * equality one per started KiB of the shorter string; a split's search the started KiB of the input times those of
 * the separator.
 */
class ArithmeticStepsTest {
  private static final long CEILING = 1 << 16;

  private static final JqEngine READER = JqEngine.builder().build();

  @Test
  void aConcatChargesOneStepPerStartedKibibyteOfItsResult() {
    // "x" + 1022, 1023 and 1024 letters: results of 1023, 1024 and 1025 bytes, one, one and two KiB started.
    long r1023 = boundary(".a + .b", concat(1022));
    long r1024 = boundary(".a + .b", concat(1023));
    long r1025 = boundary(".a + .b", concat(1024));
    assertThat(r1024 - r1023).as("a 1024-byte result against a 1023-byte one").isEqualTo(0);
    assertThat(r1025 - r1024).as("a 1025-byte result against a 1024-byte one").isEqualTo(1);
  }

  @Test
  void aDifferenceChargesOneStepPerPairCompared() {
    // [.a[]] - [.b[]]: 2 x 3 disjoint numbers compare 6 pairs, 4 x 5 compare 20; collecting them ticks once per element,
    // 5 and 9; and the difference ticks once per left element, 2 and 4 (ADR-0034 amendment, 5e4e90b). So the
    // boundaries differ by 14 + 4 + 2.
    long twoByThree = boundary("[.a[]] - [.b[]]", document("{\"a\":[1,2],\"b\":[3,4,5]}"));
    long fourByFive = boundary("[.a[]] - [.b[]]", document("{\"a\":[1,2,3,4],\"b\":[5,6,7,8,9]}"));
    assertThat(fourByFive - twoByThree).as("4 x 5 pairs against 2 x 3").isEqualTo(20);
    // [1,2] - [1,2,3]: 1 meets 1 at once, 2 meets 1 then 2: 3 pairs, against 6 for [1,2] - [3,4,5].
    long matching = boundary("[.a[]] - [.b[]]", document("{\"a\":[1,2],\"b\":[1,2,3]}"));
    assertThat(twoByThree - matching).as("6 pairs against 3 that stop at the first equal element").isEqualTo(3);
  }

  @Test
  void aSplitChargesOneStepPerPiece() {
    long three = boundary(".s / .d", document("{\"s\":\"a,b,c\",\"d\":\",\"}"));
    long four = boundary(".s / .d", document("{\"s\":\"a,b,c,d\",\"d\":\",\"}"));
    assertThat(four - three).as("four pieces against three").isEqualTo(1);
  }

  @Test
  void anObjectPlusChargesOneStepPerEntryOfItsRightOperand() {
    // The second filter looks .q up once more (2 ticks over the two keys) and gives + one more right entry (1).
    JqValue pq = document("{\"p\":1,\"q\":2}");
    long one = boundary("{x:.p} + {y:.q}", pq);
    long two = boundary("{x:.p} + {y:.q,z:.q}", pq);
    assertThat(two - one).as("two right entries against one").isEqualTo(3);
  }

  @Test
  void aMergeChargesOneStepPerEntryAtEveryLevel() {
    JqValue pq = document("{\"p\":1,\"q\":2}");
    long one = boundary("{x:.p} * {y:.q}", pq);
    long two = boundary("{x:.p} * {y:.q,z:.q}", pq);
    assertThat(two - one).as("two right entries against one").isEqualTo(3);
    // The same lookups, one more level of one entry each side: its right entry and its left entry copied (ADR-0034
    // amendment, 5e4e90b).
    long twoLevels = boundary("{k:{x:.p}} * {k:{y:.q}}", pq);
    long threeLevels = boundary("{k:{k:{x:.p}}} * {k:{k:{y:.q}}}", pq);
    assertThat(threeLevels - twoLevels).as("three merge levels against two").isEqualTo(2);
  }

  // The rates the #65 standards review added to ADR-0034 (5e4e90b), pinned by the implementer. An operand written as a
  // literal ([3,4], [[1,9,9]], [{"x":1}]) is built by the run, not folded, as the other operand is not constant; it is
  // a built value, so the operator walks it without a view's ticks, and its own cost is taken out by the same literal
  // run alone ({@link #operator}).

  @Test
  void aRepeatChargesOneStepPerStartedKibibyteOfItsResult() {
    // "x" * 1023, 1024 and 1025: one, one and two KiB started.
    long r1023 = boundary(".s * .n", document("{\"s\":\"x\",\"n\":1023}"));
    long r1024 = boundary(".s * .n", document("{\"s\":\"x\",\"n\":1024}"));
    long r1025 = boundary(".s * .n", document("{\"s\":\"x\",\"n\":1025}"));
    assertThat(r1024 - r1023).as("a 1024-byte result against a 1023-byte one").isEqualTo(0);
    assertThat(r1025 - r1024).as("a 1025-byte result against a 1024-byte one").isEqualTo(1);
  }

  @Test
  void anArrayPlusChargesOneStepPerElementCopied() {
    // .a + [3] and .a + [3,4]: the same document, one more element copied from the constant.
    JqValue a = document("{\"a\":[1,2]}");
    assertThat(operator(".a + %s", "[3,4]", a) - operator(".a + %s", "[3]", a)).as("four elements copied against three").isEqualTo(1);
  }

  @Test
  void aDifferenceChargesOneStepPerLeftElementEvenAgainstTheEmptyArray() {
    // [.a[]] - []: .[] ticks once per element, and the difference once more per left element, with no pair compared.
    long two = boundary("[.a[]] - []", document("{\"a\":[1,2]}"));
    long three = boundary("[.a[]] - []", document("{\"a\":[1,2,3]}"));
    assertThat(three - two).as("three left elements against two").isEqualTo(2);
  }

  @Test
  void aSplitChargesOneStepPerStartedKibibyteSearched() {
    // No separator in 1023, 1024 and 1025 letters: one piece each, one, one and two KiB searched.
    long r1023 = boundary(".s / .d", document("{\"s\":\"" + "y".repeat(1023) + "\",\"d\":\",\"}"));
    long r1024 = boundary(".s / .d", document("{\"s\":\"" + "y".repeat(1024) + "\",\"d\":\",\"}"));
    long r1025 = boundary(".s / .d", document("{\"s\":\"" + "y".repeat(1025) + "\",\"d\":\",\"}"));
    assertThat(r1024 - r1023).as("1024 bytes searched against 1023").isEqualTo(0);
    assertThat(r1025 - r1024).as("1025 bytes searched against 1024").isEqualTo(1);
  }

  @Test
  void aMergeChargesOneStepPerLeftEntryCopied() {
    // The same right object; one more left entry looked up (.q, 2 ticks over the two keys) and copied (1).
    JqValue pq = document("{\"p\":1,\"q\":2}");
    long one = boundary("{x:.p} * {y:.q}", pq);
    long two = boundary("{x:.p,z:.q} * {y:.q}", pq);
    assertThat(two - one).as("two left entries against one").isEqualTo(3);
    // Object + copies its left entries at the same rate (ADR-0034 amendment, 94509b6): the lookup and the copy.
    long plusOne = boundary("{x:.p} + {y:.q}", pq);
    long plusTwo = boundary("{x:.p,z:.q} + {y:.q}", pq);
    assertThat(plusTwo - plusOne).as("object + of two left entries against one").isEqualTo(3);
  }

  @Test
  void aStringEqualityChargesOneStepPerStartedKibibyteOfTheShorterString() {
    // [.a] - [.b] on two equal strings: one left element, one pair, then the string compare, one KiB started for 1023
    // and 1024 bytes and two for 1025. A longer right string changes nothing: the shorter one counts.
    long r1023 = boundary("[.a] - [.b]", strings(1023, 1023));
    long r1024 = boundary("[.a] - [.b]", strings(1024, 1024));
    long r1025 = boundary("[.a] - [.b]", strings(1025, 1025));
    assertThat(r1024 - r1023).as("1024 bytes compared against 1023").isEqualTo(0);
    assertThat(r1025 - r1024).as("1025 bytes compared against 1024").isEqualTo(1);
    assertThat(boundary("[.a] - [.b]", strings(1024, 3000)) - r1024).as("the shorter string of 1024 bytes against 3000").isEqualTo(0);
  }

  @Test
  void aSplitChargesTheKibibytesSearchedTimesTheKibibytesOfTheSeparator() {
    // 1025 letters (two KiB) without the separator: one piece; a separator of 1024 bytes (one KiB) charges 2 x 1, one
    // of 1025 bytes (two KiB) 2 x 2.
    long one = boundary(".s / .d", document("{\"s\":\"" + "y".repeat(1025) + "\",\"d\":\"" + "z".repeat(1024) + "\"}"));
    long two = boundary(".s / .d", document("{\"s\":\"" + "y".repeat(1025) + "\",\"d\":\"" + "z".repeat(1025) + "\"}"));
    assertThat(two - one).as("a two-KiB separator against a one-KiB one").isEqualTo(2);
  }

  @Test
  void anEmptySeparatorCountsAsOneKibibyte() {
    // .s / "" on 1025 letters against 1024: one KiB more searched, times one for the empty separator, and one piece
    // more, one per letter.
    long r1024 = boundary(".s / \"\"", document("{\"s\":\"" + "y".repeat(1024) + "\"}"));
    long r1025 = boundary(".s / \"\"", document("{\"s\":\"" + "y".repeat(1025) + "\"}"));
    assertThat(r1025 - r1024).as("1025 letters split by the empty separator against 1024").isEqualTo(2);
  }

  /** {"a":<a letters x>,"b":<b letters x>}. */
  private static JqValue strings(int a, int b) {
    return document("{\"a\":\"" + "x".repeat(a) + "\",\"b\":\"" + "x".repeat(b) + "\"}");
  }

  @Test
  void anEqualityChargesOneStepPerMemberPairCompared() {
    // [.a + []] - [[1,9,9]]: the left array is built, so its walk ticks nothing; 1 = 1, then 2 against 9 ends it after
    // two pairs. Against [[1,2,9]] three pairs: 1, 2, then 3 against 9.
    JqValue a = document("{\"a\":[1,2,3]}");
    long two = operator("[.a + []] - %s", "[[1,9,9]]", a);
    long three = operator("[.a + []] - %s", "[[1,2,9]]", a);
    assertThat(three - two).as("three member pairs against two").isEqualTo(1);
  }

  @Test
  void anEqualityChargesOneStepPerKeyItCountsOfTheRightObject() {
    // [.a] - [{"x":1}] and [.a] - [{"x":1,"y":2}]: one member pair each, the key x first in both, then the right
    // object's keys counted, one against two.
    JqValue a = document("{\"a\":{\"x\":1}}");
    long one = operator("[.a] - %s", "[{\"x\":1}]", a);
    long two = operator("[.a] - %s", "[{\"x\":1,\"y\":2}]", a);
    assertThat(two - one).as("two right keys counted against one").isEqualTo(1);
  }

  /** {"a":"x","b":<letters>}: under 2 KiB whatever the letters. */
  private static JqValue concat(int letters) {
    return document("{\"a\":\"x\",\"b\":\"" + "y".repeat(letters) + "\"}");
  }

  /** The boundary of {@code shape} with {@code literal} in it, less the boundary of {@code literal} alone. */
  private static long operator(String shape, String literal, JqValue input) {
    return boundary(shape.formatted(literal), input) - boundary(literal, input);
  }

  /** The least step budget under which {@code filter} on {@code input} completes. */
  private static long boundary(String filter, JqValue input) {
    assertThat(completes(filter, input, CEILING)).as("%s completes with %d steps", filter, CEILING).isTrue();
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
    JqEngine engine = steps(limit);
    try {
      return engine.compile(filter).run(input, NONE, new Results(), engine.newWorkspace());
    } catch (JqTermination t) {
      assertThat(t.budget()).as("budget that ended %s with %d steps", filter, limit).isEqualTo(Budget.STEPS);
      return false;
    }
  }

  private static JqValue document(String json) {
    return read(READER.newWorkspace(), json.getBytes(UTF_8)).root();
  }
}
