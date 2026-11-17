package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * DEVIATIONS "Huge constant keys" (ADR-0035, #65 standards reviews): every fold of a compile, the key-kind check
 * included, charges one budget of 1 MiB of bytes built and 2^20 steps, each node folded once, so an untrusted filter
 * cannot make the compile allocate gigabytes or work without a limit. jq folds {@code {(("x"*1e9)/"x"):1}} whole at
 * parse time: a 1 GB string, then 10^9 pieces. jqj's fold stops at the budget before it allocates the string, so the
 * compile is quick (a time witness: the whole fold would take far longer than the limit, or run out of heap), and the
 * key is left to the run, whose own bytes-built budget governs it.
 */
class HugeConstantKeyTest {
  private static final String HUGE = "{((\"x\"*1e9)/\"x\"):1}";

  @Test
  void theCompileOfAHugeConstantKeyBuildsNoGigabyte() {
    JqEngine engine = bytesBuilt(1 << 20);
    JqFilter filter = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> engine.compile(HUGE), "the compile of " + HUGE);
    // Under 1 MiB built the run ends with the budget, charged before the string is allocated.
    assertBudget(() -> filter.run(JqNull.NULL, NONE, new Results(), engine.newWorkspace()), Budget.BYTES_BUILT, HUGE + " under 1 MiB built");
  }

  @Test
  void theFourKibibyteCapHoldsForTheWholeFold() {
    // ADR-0035 (94509b6): [("x"*n),("x"*n)] + [] folds two strings of 40 + n bytes and an array of 64 + 2 x 8, all
    // under one budget of 40 + 4096. For n = 1988 that is 4136, a Const the run builds nothing for; for n = 1989 it is
    // 4138, so nothing folds whole (each repeat alone still does) and the run builds the array: under 0 bytes built it
    // ends with the budget (constant-key-cap/fold-whole-array gives jq-ref's output for n = 4000).
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(pair(1988)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of %s under 0 bytes built", pair(1988)).hasSize(1);
    assertBudget(
        () -> zero.compile(pair(1989)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()), Budget.BYTES_BUILT, pair(1989) + " under 0 bytes built");
  }

  @Test
  void theFoldsOfOneCompileBuildAtMostOneMebibyte() {
    // ADR-0035 (bd34167, 31aca88): every fold of a compile shares 1 MiB of bytes built. Each ("x"*4096) folds to a
    // Const of 40 + 4096 bytes after its literal "x" of 40 + 1, 4177 in all, so 251 of them fold (1 048 427 bytes) and
    // the 252nd (1 052 604) is left to the run, which builds it: under 0 bytes built the run of 251 gives them all, the
    // run of 252 ends with the budget.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(strings(251)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of 251 strings of 4096 bytes under 0 bytes built").hasSize(251);
    assertBudget(
        () -> zero.compile(strings(252)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "252 strings of 4096 bytes under 0 bytes built");
  }

  @Test
  void aFoldsLiteralsAreChargedToTheCompile() {
    // ADR-0035 (31aca88): the array literal of [1,...,1] + . is built by its fold, so it is charged, 64 + 8 x m (a
    // number is no charge, and . with + folds to nothing more). After it ("x"*4096) needs 4177: m = 130541 leaves
    // that (1 048 569 bytes in all), so the repeat folds, and the run, which never reaches the array, builds nothing
    // under 0 bytes built; m = 130542 (1 048 577) leaves the repeat to the run, which ends with the budget.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(afterLiteral(130541)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results after a literal of 130541 elements under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(afterLiteral(130542)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a repeat after a literal of 130542 elements under 0 bytes built");
  }

  @Test
  void anArrayLiteralFoldsInLinearTime() {
    // ADR-0035 (31aca88): no fold work goes uncharged. The fold of [1,...,1] + . walks the literal's 10^6 elements once
    // before it charges them (8 000 064 bytes, past the compile's 1 MiB, so it is none); a walk that put each element
    // in front of the others would move 5 x 10^11 references, far past the limit (a time witness).
    String filter = "[" + String.join(",", Collections.nCopies(1_000_000, "1")) + "] + .";
    JqEngine engine = RunBudgets.unlimited();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> engine.compile(filter), "the compile of a literal of 10^6 elements + .");
  }

  @Test
  void anObjectLiteralIsChargedAsAConstruction() {
    // {a:1} + . charges its name as a string, 40 + 1, and itself as a container and one entry, 64 + 16: 121. After an
    // array literal of m elements and it, ("x"*4096) needs 4177: m = 130526 leaves that (1 048 570 bytes in all), m =
    // 130527 (1 048 578) does not, so the repeat is left to the run.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(afterObject(130526, "{a:1}")).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results after an object and a literal of 130526 elements under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(afterObject(130527, "{a:1}")).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a repeat after an object and a literal of 130527 elements under 0 bytes built");
    // {a:1,b:1,c:1} charges three names, 3 x 41, and a container of three entries, 64 + 3 x 16: 235, so m = 130512
    // leaves 4177 (1 048 572 bytes in all) and m = 130513 (1 048 580) does not. An entry charge not multiplied by the
    // pairs, divided by them or added to their count would charge 203, 192 or 206, and fold the repeat at 130513.
    Results three = new Results();
    zero.compile(afterObject(130512, "{a:1,b:1,c:1}")).run(JqNull.NULL, NONE, three, zero.newWorkspace());
    assertThat(three.values).as("results after an object of three pairs and a literal of 130512 elements").hasSize(1);
    assertBudget(
        () -> zero.compile(afterObject(130513, "{a:1,b:1,c:1}")).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a repeat after an object of three pairs and a literal of 130513 elements under 0 bytes built");
  }

  @Test
  void aLocationIsChargedAsAnObjectOfTwoEntries() {
    // ADR-0035's #65 amendment, #67's standards review: $__loc__ + . folds $__loc__ to an object of two entries whose
    // strings are shared, 64 + 2 x 16 = 96. After an array literal of m elements and it, ("x"*4096) needs 4177: m =
    // 130529 leaves that (1 048 569 bytes in all), m = 130530 (1 048 577) does not, so the repeat is left to the run.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(afterObject(130529, "$__loc__")).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results after $__loc__ and a literal of 130529 elements under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(afterObject(130530, "$__loc__")).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a repeat after $__loc__ and a literal of 130530 elements under 0 bytes built");
    // {$__loc__} + . charges its key "__loc__" as a string, 40 + 7, the location, 96, and itself as a container and one
    // entry, 64 + 16: 223. m = 130514 leaves 4177 (exactly 1 048 576 bytes in all), m = 130515 (1 048 584) does not.
    Results pair = new Results();
    zero.compile(afterObject(130514, "{$__loc__}")).run(JqNull.NULL, NONE, pair, zero.newWorkspace());
    assertThat(pair.values).as("results after {$__loc__} and a literal of 130514 elements under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(afterObject(130515, "{$__loc__}")).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a repeat after {$__loc__} and a literal of 130515 elements under 0 bytes built");
  }

  @Test
  void theCapCountsTheKeptFoldsAFoldUses() {
    // The parser's key checks fold ("x"*n) and ("y"*n), 40 + n bytes each, and keep them; the resolver's fold of the
    // + uses them. For n = 1000 the whole fold is under 40 + 4096 and is a Const, so the run builds nothing under 0
    // bytes built; for n = 3000 it is past the cap, though its own operator built far less, so the run builds it.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(keyed(1000)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of %s under 0 bytes built", "keyed(1000)").hasSize(1);
    assertBudget(
        () -> zero.compile(keyed(3000)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()), Budget.BYTES_BUILT, "keyed(3000) under 0 bytes built");
  }

  @Test
  void eachNodeIsFoldedOnceInACompile() {
    // ADR-0035 (31aca88): the resolver asks for the fold of each + of [1,...,1] + . + ... + ., n levels, but the
    // literal of m = 1000 elements is folded, and charged, once: 8064 bytes, not n x 8064. With n = 200, folding it
    // at every level would charge 1 612 800 bytes, past the compile's 1 MiB, and leave ("x"*4096) after it to the run;
    // folded once, the repeat still folds, so the run, which never reaches the chain, builds nothing.
    JqEngine zero = bytesBuilt(0);
    String chain = "[" + String.join(",", Collections.nCopies(1000, "1")) + "]" + " + .".repeat(200);
    Results folded = new Results();
    zero.compile("(empty | " + chain + "), (\"x\"*4096)").run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of a repeat after a chain of 200 + under 0 bytes built").hasSize(1);
  }

  @Test
  void theFoldsOfOneCompileTakeAtMostTwoToTheTwentiethSteps() {
    // ADR-0035 (bd34167): every fold of a compile shares 2^20 steps. A difference of n ones less 1023 twos and a one
    // ticks per left element and per pair compared up to the one, n x 1024, and a few steps more (ADR-0034). n = 1023
    // folds alone and n = 1024 does not; a fold the run, under 0 bytes built, needs to build nothing for, one it is
    // left to, whose arrays it builds, ends with the budget (constant-key-cap/difference-past-the-step-budget gives
    // jq-ref's output for a difference past the steps).
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(difference(1023)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of a difference of 1023 ones under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(difference(1024)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a difference of 1024 ones under 0 bytes built");
    // Each of two differences of 512 ones folds alone, but together they pass 2^20: the second is left to the run.
    Results half = new Results();
    zero.compile(difference(512)).run(JqNull.NULL, NONE, half, zero.newWorkspace());
    assertThat(half.values).as("results of a difference of 512 ones under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(difference(512) + "," + difference(512)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "two differences of 512 ones under 0 bytes built");
  }

  @Test
  void aFoldPastTheStepsLeavesTheLaterFoldsToTheRun() {
    // ADR-0035 (bd34167): after a difference of 1022 ones the compile has more than 1024 steps left, enough for the 4
    // ticks of ("x"*4096), which folds; after one of 1023 it has fewer than 4 (the few steps more of difference(n)),
    // so the repeat is left to the run. The run never reaches the difference: under 0 bytes built the first gives the
    // string, the second ends with the budget.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile("(empty | " + difference(1022) + "), (\"x\"*4096)").run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of a repeat after a difference of 1022 ones under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile("(empty | " + difference(1023) + "), (\"x\"*4096)").run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a repeat after a difference of 1023 ones under 0 bytes built");
  }

  private static String pair(int n) {
    return "[(\"x\"*" + n + "),(\"x\"*" + n + ")] + []";
  }

  /** {@code n} strings of 4096 bytes, each the fold of a repeat, joined by commas. */
  private static String strings(int n) {
    return String.join(",", Collections.nCopies(n, "(\"x\"*4096)"));
  }

  /** {@code (empty | [1,...,1] + .), ("x"*4096)}: a repeat after a literal of {@code m} elements, unreached. */
  private static String afterLiteral(int m) {
    return "(empty | [" + String.join(",", Collections.nCopies(m, "1")) + "] + .), (\"x\"*4096)";
  }

  /** {@code (empty | [1,...,1] + ., object + .), ("x"*4096)}: a repeat after a literal and an object, unreached. */
  private static String afterObject(int m, String object) {
    return "(empty | [" + String.join(",", Collections.nCopies(m, "1")) + "] + ., " + object + " + .), (\"x\"*4096)";
  }

  /** {@code {(("x"*n)):1} + {(("y"*n)):2}}: two objects whose keys the parser's checks fold. */
  private static String keyed(int n) {
    return "{((\"x\"*" + n + ")):1} + {((\"y\"*" + n + ")):2}";
  }

  /** {@code [1,...,1] - [2,...,2,1]}: {@code n} ones less 1023 twos and a one, n x 1024 ticks and a few more. */
  private static String difference(int n) {
    return "[" + String.join(",", Collections.nCopies(n, "1")) + "] - [" + "2,".repeat(1023) + "1]";
  }
}
