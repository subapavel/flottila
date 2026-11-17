package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes an operator builds, at the public API (ADR-0035 and its #65 amendment, #64's hand-over). An operator
 * charges its result by the value it builds, before it allocates: a string STRING (40 B) plus its UTF-8 length, an
 * array CONTAINER (64 B) plus ELEMENT (8 B) per element kept, an object CONTAINER plus ENTRY (16 B) per distinct key as
 * it enters, at every merge level; an operand returned unchanged charges nothing, and neither does a number. Each pin
 * is an adjacent pair, as in BytesBuiltBudgetTest: the run completes under N bytes built and ends under N - 1 with
 * {@code JqTermination(BUDGET, BYTES_BUILT)}; each rate has two such pins. Operands come from documents read on another
 * engine's Workspace, so no fold makes them constants and the read charges nothing. A fold at parse time builds at most
 * 4 KiB (ADR-0035 amendment), so a short folded value costs the run nothing while a longer one is built and charged at
 * run time. The results are jq-ref 1.8.2's, observed 2026-11-13 (scratchpad probes of the test-author, and the
 * recordings named at each test).
 */
class ArithmeticBudgetTest {
  private static final long CEILING = 1 << 20;

  private static final JqEngine READER = JqEngine.builder().build();

  @Test
  void aStringConcatChargesAStringAndItsUtf8Length() {
    // "ab" + "cde" is "abcde": 40 + 5; "ab" + "cdefgh" 40 + 8; "é" + "ü" is four bytes (two code points): 40 + 4.
    assertCut(".a + .b", document("{\"a\":\"ab\",\"b\":\"cde\"}"), 45, "\"abcde\"");
    assertCut(".a + .b", document("{\"a\":\"ab\",\"b\":\"cdefgh\"}"), 48, "\"abcdefgh\"");
    assertCut(".a + .b", document("{\"a\":\"é\",\"b\":\"ü\"}"), 44, "\"éü\"");
  }

  @Test
  void aRepeatChargesAStringAndItsLength() {
    // jq-ref: .n * .s on {"s":"ab","n":3} gives "ababab" (arith-repeat/input-number-left).
    assertCut(".s * .n", document("{\"s\":\"ab\",\"n\":3}"), 46, "\"ababab\"");
    assertCut(".s * .n", document("{\"s\":\"ab\",\"n\":5}"), 50, "\"ababababab\"");
    assertCut(".n * .s", document("{\"s\":\"ab\",\"n\":3}"), 46, "\"ababab\"");
  }

  @Test
  void theRepeatBoundIsJqsErrorRaisedBeforeAnyCharge() {
    // arith-repeat/too-long-input: 2147483639 bytes or more is "Repeat string result too long", which jqj raises without
    // allocating: under 100 bytes built it is the catchable error, not the budget, and so it is with no limit at all (an
    // allocation of 2 GiB would end otherwise). Just under the bound, jq-ref builds "ab" * 1073741819 (2147483638 bytes,
    // observed with | length); with 1 MiB jqj charges it before it allocates and ends with the budget.
    JqValue tooLong = document("{\"s\":\"a\",\"n\":2147483639}");
    assertRepeatTooLong(bytesBuilt(100), tooLong, "under 100 bytes built");
    assertRepeatTooLong(RunBudgets.unlimited(), tooLong, "with no limit");
    JqEngine mebibyte = bytesBuilt(CEILING);
    assertBudget(
        () -> mebibyte.compile(".s * .n").run(document("{\"s\":\"ab\",\"n\":1073741819}"), NONE, new Results(), mebibyte.newWorkspace()),
        Budget.BYTES_BUILT,
        "\"ab\" * 1073741819 under 1 MiB built");
  }

  @Test
  void aSplitChargesItsArrayAndEachPiece() {
    // ["a","b","c"]: 64 + 3 x 8 + 3 x (40 + 1) = 211; four pieces 64 + 32 + 4 x 41 = 260 (arith-divmod/split-commas).
    assertCut(".s / .d", document("{\"s\":\"a,b,c\",\"d\":\",\"}"), 211, "[\"a\",\"b\",\"c\"]");
    assertCut(".s / .d", document("{\"s\":\"a,b,c,d\",\"d\":\",\"}"), 260, "[\"a\",\"b\",\"c\",\"d\"]");
  }

  @Test
  void anArrayConcatChargesAContainerAndEachElement() {
    // arith-containers/array-concat-input: [1,2] + [3] is [1,2,3], 64 + 3 x 8; one more element 8 more.
    assertCut(".a + .b", document("{\"a\":[1,2],\"b\":[3]}"), 88, "[1,2,3]");
    assertCut(".a + .b", document("{\"a\":[1,2],\"b\":[3,4]}"), 96, "[1,2,3,4]");
  }

  @Test
  void aDifferenceChargesTheElementsItKeeps() {
    // [1,2,3] - [2] keeps two: 64 + 16; [1,2,3,4] - [2] keeps three: 64 + 24.
    assertCut(".a - .b", document("{\"a\":[1,2,3],\"b\":[2]}"), 80, "[1,3]");
    assertCut(".a - .b", document("{\"a\":[1,2,3,4],\"b\":[2]}"), 88, "[1,3,4]");
  }

  @Test
  void anObjectPlusChargesEachDistinctKey() {
    // {"x":1} + {"y":2}: 64 + 2 x 16; a third key 16 more; a repeated key enters once (arith-containers/
    // object-plus-repeated-key: the right value wins).
    assertCut(".a + .b", document("{\"a\":{\"x\":1},\"b\":{\"y\":2}}"), 96, "{\"x\":1,\"y\":2}");
    assertCut(".a + .b", document("{\"a\":{\"x\":1},\"b\":{\"y\":2,\"z\":3}}"), 112, "{\"x\":1,\"y\":2,\"z\":3}");
    assertCut(".a + .b", document("{\"a\":{\"x\":1},\"b\":{\"x\":2}}"), 80, "{\"x\":2}");
  }

  @Test
  void aMergeChargesAnObjectAtEveryLevel() {
    // One level as +: 96. Two levels: the outer {"k":…} 64 + 16 and the merged inner 64 + 32, 176; three 256: each
    // level 80 (arith-containers/merge-input).
    assertCut(".a * .b", document("{\"a\":{\"x\":1},\"b\":{\"y\":2}}"), 96, "{\"x\":1,\"y\":2}");
    assertCut(".a * .b", document("{\"a\":{\"k\":{\"x\":1}},\"b\":{\"k\":{\"y\":2}}}"), 176, "{\"k\":{\"x\":1,\"y\":2}}");
    assertCut(".a * .b", document("{\"a\":{\"k\":{\"k\":{\"x\":1}}},\"b\":{\"k\":{\"k\":{\"y\":2}}}}"), 256, "{\"k\":{\"k\":{\"x\":1,\"y\":2}}}");
  }

  @Test
  void textFormatChargesItsTextAfterTheDump() {
    // #64's hand-over and the ADR-0035 amendment: @text charges STRING + its length after the scratch dump and before
    // the value exists; a string is returned unchanged and charges nothing (jq-ref: @text on [1,2,3] gives "[1,2,3]").
    assertCut("@text", document("[1,2,3]"), 47, "\"[1,2,3]\"");
    assertCut("@text", document("[1,2,3,4]"), 49, "\"[1,2,3,4]\"");
    assertThat(results("@text", document("\"abc\""), 0)).as("@text of a string under 0 bytes built").containsExactly("\"abc\"");
  }

  @Test
  void numbersAndAnOperandReturnedUnchangedChargeNothing() {
    // .a + .b on {"a":1,"b":2} builds a number at run time (1 + 2 would fold at parse time); null + . and . + null
    // return the document's array (arith-containers/input-object-plus-null-keeps-order). The witness: [.[]] on the
    // same array ends under 0 bytes built.
    JqValue three = document("[1,2,3]");
    assertThat(results(".a + .b", document("{\"a\":1,\"b\":2}"), 0)).as(".a + .b under 0 bytes built").containsExactly("3");
    assertThat(results("null + .", three, 0)).as("null + . under 0 bytes built").containsExactly("[1,2,3]");
    assertThat(results(". + null", three, 0)).as(". + null under 0 bytes built").containsExactly("[1,2,3]");
    JqEngine zero = bytesBuilt(0);
    assertBudget(() -> zero.compile("[.[]]").run(three, NONE, new Results(), zero.newWorkspace()), Budget.BYTES_BUILT, "[.[]] under 0 bytes built");
  }

  @Test
  void aShortFoldedKeyChargesNothingAndALongOneIsBuiltAtRunTime() {
    // ADR-0035 amendment: a fold builds at most 4 KiB. {("x"*10):1} folds its key, so the run builds only the object,
    // 64 + 16, as {a:1}; {("x"*5000):1} leaves the repeat to the run: 40 + 5000 for the key, 80 for the object, 5120
    // (constant-key-fold/string-short and string-past-the-cap give {"xxxxxxxxxx":1} and the 5000-byte key). At the cap,
    // 4096 bytes are not larger than 4 KiB and fold; 4097 are built, 40 + 4097 + 80.
    assertCut("{(\"x\"*10):1}", JqNull.NULL, 80, "{\"xxxxxxxxxx\":1}");
    assertCut("{(\"x\"*5000):1}", JqNull.NULL, 5120, "{\"" + "x".repeat(5000) + "\":1}");
    assertCut("{(\"x\"*4096):1}", JqNull.NULL, 80, "{\"" + "x".repeat(4096) + "\":1}");
    assertCut("{(\"x\"*4097):1}", JqNull.NULL, 4217, "{\"" + "x".repeat(4097) + "\":1}");
    // Outside a key the same holds: a folded string costs the run nothing, a long repeat its string.
    assertThat(results("\"ab\" + \"cd\"", JqNull.NULL, 0)).as("\"ab\" + \"cd\" under 0 bytes built").containsExactly("\"abcd\"");
    assertCut("\"x\" * 5000", JqNull.NULL, 5040, "\"" + "x".repeat(5000) + "\"");
  }

  private static void assertRepeatTooLong(JqEngine engine, JqValue input, String what) {
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as(".s * .n on {\"s\":\"a\",\"n\":2147483639} %s", what)
            .isThrownBy(() -> engine.compile(".s * .n").run(input, NONE, new Results(), engine.newWorkspace()))
            .actual();
    assertThat(e.value()).as("error value %s", what).isInstanceOf(JqString.class);
    assertThat(((JqString) e.value()).value()).as("error text %s", what).isEqualTo("Repeat string result too long");
  }

  /** As BytesBuiltBudgetTest: completes under {@code limit} with {@code texts}, ends under {@code limit - 1}. */
  private static void assertCut(String filter, JqValue input, long limit, String... texts) {
    assertThat(results(filter, input, limit)).as("results of %s under %d bytes built", filter, limit).containsExactly(texts);
    JqEngine under = bytesBuilt(limit - 1);
    Results none = new Results();
    assertBudget(() -> under.compile(filter).run(input, NONE, none, under.newWorkspace()), Budget.BYTES_BUILT, filter + " under " + (limit - 1) + " bytes built");
    assertThat(none.values).as("results of %s under %d bytes built", filter, limit - 1).isEmpty();
  }

  private static List<String> results(String filter, JqValue input, long limit) {
    JqEngine engine = bytesBuilt(limit);
    Results results = new Results();
    assertThat(engine.compile(filter).run(input, NONE, results, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }

  private static JqValue document(String json) {
    return read(READER.newWorkspace(), json.getBytes(UTF_8)).root();
  }
}
