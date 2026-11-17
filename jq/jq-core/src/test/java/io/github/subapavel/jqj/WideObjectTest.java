package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * Wide read objects walk in about linear time and steps (F-40, ADR-0044, #85): a walk first proves its keys, one step
 * per key entry, then {@code .[]} charges one per member and the Printer none, the writer one per started KiB of each
 * drain (F-23); a key set built to collide in one hash falls back to a sort of at most n * ceil(log2 n) compares, never
 * n * n. Equality takes a read right side through its key index (ADR-0044 as amended by #95): n + m + one step per
 * member pair, not a field read that scans the right side's m keys per left member. Each run has a wall-clock bound in a
 * separate thread, so a quadratic walk fails the test instead of stalling the build. On 25ef36c {@code -c .} over
 * 78 841 keys takes 39.9 s (#85).
 */
class WideObjectTest {
  /** 10^5 keys. */
  private static final int WIDE = 100_000;

  /**
   * {@code {"k0":0,…,"k99999":99999}}, compact. jq-ref 1.8.2, observed 2026-11-13: {@code [range(100000) | {key:
   * "k\(.)", value: .}] | from_entries} prints it under {@code -c} in 1 477 782 bytes with the LF, {@code -c .} prints it
   * unchanged and {@code [.[]] == [range(100000)]} gives true.
   */
  private static final byte[] WIDE_OBJECT = object(WIDE, i -> "k" + i);

  /** 2^15 keys. */
  private static final int COLLIDING = 1 << 15;

  /**
   * 2^15 keys of 15 blocks each, block b {@code BB} where bit b of the member's index is set and {@code Aa} where it is
   * not, every value its index: {@code Aa} and {@code BB} have one {@code String.hashCode}, so all the keys do (#68's
   * second standards review). jq-ref 1.8.2, observed 2026-11-13: the object built by {@code from_entries} from that rule
   * is 1 266 844 bytes under {@code -c} with the LF, its first key 15 {@code Aa}, its second {@code BB} and 14
   * {@code Aa}, its last 15 {@code BB} with the value 32767; it prints unchanged under {@code -c .}, has length 32768,
   * and {@code [.[]] == [range(32768)]} gives true.
   */
  private static final byte[] COLLIDING_OBJECT = object(COLLIDING, WideObjectTest::blocks);

  /** The key of member {@code i} of {@link #COLLIDING_OBJECT}. */
  private static String blocks(int i) {
    StringBuilder key = new StringBuilder(30);
    for (int b = 0; b < 15; b++) key.append((i >> b & 1) == 1 ? "BB" : "Aa");
    return key.toString();
  }

  /** {@code {"key(0)":0,…}} with {@code n} members, compact, in ASCII. */
  private static byte[] object(int n, IntFunction<String> key) {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = 0; i < n; i++) object.add("\"" + key.apply(i) + "\":" + i);
    return object.toString().getBytes(US_ASCII);
  }

  /** {@code {"key(n-1)":n-1,…,"key(0)":0}}: the members of {@link #object} in reverse order, compact, in ASCII. */
  private static byte[] reversed(int n, IntFunction<String> key) {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = n - 1; i >= 0; i--) object.add("\"" + key.apply(i) + "\":" + i);
    return object.toString().getBytes(US_ASCII);
  }

  /** {@code [left,right]}, compact. */
  private static byte[] pair(byte[] left, byte[] right) {
    byte[] pair = new byte[left.length + right.length + 3];
    pair[0] = '[';
    System.arraycopy(left, 0, pair, 1, left.length);
    pair[left.length + 1] = ',';
    System.arraycopy(right, 0, pair, left.length + 2, right.length);
    pair[pair.length - 1] = ']';
    return pair;
  }

  /**
   * {@code [W,Wrev]}: {@link #WIDE_OBJECT}, then its members in reverse order. jq-ref 1.8.2, observed 2026-11-14 (the
   * test-author's {@code .logs/95-jqref.log}): {@code [range(100000) | {key: "k\(.)", value: .}] | from_entries} and the
   * same over {@code range(99999; -1; -1)}, slurped by {@code -sc .}, print it in 2 955 566 bytes with the LF, the
   * second object's first member {@code "k99999":99999}, and {@code .[0] == .[1]} gives true.
   */
  private static final byte[] WIDE_PAIR = pair(WIDE_OBJECT, reversed(WIDE, i -> "k" + i));

  /**
   * {@link #WIDE_PAIR} with the right side's {@code k99999}, its first member, renamed {@code x99999}: one length, so
   * one byte size. jq-ref 1.8.2, observed 2026-11-14 ({@code .logs/95-jqref.log}): built as {@link #WIDE_PAIR} with that
   * key, {@code -sc .} prints it in 2 955 566 bytes with the LF, the second object's first member
   * {@code "x99999":99999}, and {@code .[0] == .[1]} gives false.
   */
  private static final byte[] WIDE_PAIR_RENAMED = pair(WIDE_OBJECT, reversed(WIDE, i -> (i == WIDE - 1 ? "x" : "k") + i));

  /**
   * {@code [C,Crev]}: {@link #COLLIDING_OBJECT}, then its members in reverse order. jq-ref 1.8.2, observed 2026-11-14
   * ({@code .logs/95-jqref.log}): the two objects built by {@code from_entries} from {@link #blocks}' rule, over
   * {@code range(32768)} and {@code range(32767; -1; -1)}, slurped by {@code -sc .}, print it in 2 533 690 bytes with the
   * LF, the second object's first member {@code "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB":32767}, {@code map(length)} gives
   * {@code [32768,32768]} and {@code .[0] == .[1]} gives true.
   */
  private static final byte[] COLLIDING_PAIR = pair(COLLIDING_OBJECT, reversed(COLLIDING, WideObjectTest::blocks));

  /** {@code 0} to {@code n - 1}, the texts of the members' values. */
  private static List<String> indexes(int n) {
    String[] texts = new String[n];
    for (int i = 0; i < n; i++) texts[i] = Integer.toString(i);
    return List.of(texts);
  }

  /** The first index where {@code actual} and {@code expected} differ, -1 when they are equal; no list is printed. */
  private static int mismatch(List<String> actual, List<String> expected) {
    int n = Math.min(actual.size(), expected.size());
    for (int i = 0; i < n; i++) {
      if (!actual.get(i).equals(expected.get(i))) return i;
    }
    return actual.size() == expected.size() ? -1 : n;
  }

  /** {@code document} and an LF: what jq-ref prints for a compact document of distinct keys under {@code -c .}. */
  private static byte[] line(byte[] document) {
    byte[] line = Arrays.copyOf(document, document.length + 1);
    line[document.length] = '\n';
    return line;
  }

  /** {@code .[]} over {@code document} completes with {@code steps} steps, giving {@code values}. */
  private static void assertIterates(byte[] document, long steps, List<String> values, String what) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, document).root();
    Results all = new Results();
    boolean[] answer = new boolean[1];
    assertThatCode(() -> answer[0] = exact.compile(".[]").run(root, NONE, all, whole)).as("run of .[] over %s with %s steps", what, steps).doesNotThrowAnyException();
    assertThat(answer[0]).as("run's answer for .[] over %s with %s steps", what, steps).isTrue();
    List<String> texts = all.values.stream().map(JqValue::toJson).toList();
    assertThat(mismatch(texts, values)).as("first wrong result of .[] over %s, of %s results", what, texts.size()).isEqualTo(-1);
  }

  /** {@code -c .} over {@code document} completes with {@code steps} steps, printing {@code line}. */
  private static void assertPrints(byte[] document, long steps, byte[] line, String what) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, document).root();
    RecordingTarget target = new RecordingTarget();
    boolean[] answer = new boolean[1];
    assertThatCode(() -> answer[0] = exact.compile(".").run(root, NONE, writer(whole, target), whole)).as("run of -c . over %s with %s steps", what, steps).doesNotThrowAnyException();
    assertThat(answer[0]).as("run's answer for -c . over %s with %s steps", what, steps).isTrue();
    byte[] printed = target.bytes();
    assertThat(Arrays.mismatch(printed, line)).as("first wrong byte of -c . over %s, of %s printed", what, printed.length).isEqualTo(-1);
  }

  /** {@code filter} over {@code document} ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps} steps. */
  private static void assertEnds(String filter, boolean print, byte[] document, long steps, String what) {
    JqEngine under = steps(steps);
    Workspace cut = under.newWorkspace();
    JqValue root = read(cut, document).root();
    JqSink sink = print ? writer(cut, new RecordingTarget()) : new Results();
    assertBudget(() -> under.compile(filter).run(root, NONE, sink, cut), Budget.STEPS, filter + " over " + what + " with " + steps + " steps");
  }

  /** {@code filter} over {@code document} completes with {@code steps} steps, giving the one result {@code result}. */
  private static void assertGives(String filter, byte[] document, long steps, String result, String what) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, document).root();
    Results all = new Results();
    boolean[] answer = new boolean[1];
    assertThatCode(() -> answer[0] = exact.compile(filter).run(root, NONE, all, whole)).as("run of %s over %s with %s steps", filter, what, steps).doesNotThrowAnyException();
    assertThat(answer[0]).as("run's answer for %s over %s with %s steps", filter, what, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("result of %s over %s", filter, what).containsExactly(result);
  }

  /** The writer's ticks for one printed line of {@code bytes}: 64 KiB drains and the rest, one per started KiB (F-23). */
  private static long writerTicks(int bytes) {
    return (bytes + 1023) / 1024;
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void iterateTenToTheFiveKeys() {
    // ADR-0044: 1 477 781 bytes read, 1 444 ticks (ADR-0015, one per started KiB), the proof one tick per key entry
    // (10^5) and the loop one per member (10^5): 201 444 complete and 201 443 end.
    assertThat(WIDE_OBJECT).as("the 10^5-key document, jq-ref's -c line without its LF").hasSize(1_477_781);
    assertIterates(WIDE_OBJECT, 201_444, indexes(WIDE), "10^5 keys");
    assertEnds(".[]", false, WIDE_OBJECT, 201_443, "10^5 keys");
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void printTenToTheFiveKeys() {
    // ADR-0044: 1 444 read ticks, the proof's 10^5 and no tick per member from the Printer; the writer charges its KiB
    // of the 1 477 782 bytes it prints (1 444, the 64 KiB drains and the last one).
    byte[] line = line(WIDE_OBJECT);
    long steps = 1_444 + WIDE + writerTicks(line.length);
    assertPrints(WIDE_OBJECT, steps, line, "10^5 keys");
    assertEnds(".", true, WIDE_OBJECT, steps - 1, "10^5 keys");
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void equalsItselfTenToTheFiveKeys() {
    // ADR-0044: Equality proves its left side, 10^5 ticks, then finds the right side the same view, jq's pointer check
    // (ADR-0029), and compares no member: 1 444 + 10^5 = 101 444 complete and 101 443 end. jq-ref 1.8.2, observed
    // 2026-11-13: . == . on the object [range(100000) | {key: "k\(.)", value: .}] | from_entries gives true.
    JqEngine exact = steps(101_444);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, WIDE_OBJECT).root();
    Results all = new Results();
    boolean[] answer = new boolean[1];
    assertThatCode(() -> answer[0] = exact.compile(". == .").run(root, NONE, all, whole)).as("run of . == . over 10^5 keys with 101 444 steps").doesNotThrowAnyException();
    assertThat(answer[0]).as("run's answer for . == . over 10^5 keys").isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("result of . == . over 10^5 keys").containsExactly("true");
    assertEnds(". == .", false, WIDE_OBJECT, 101_443, "10^5 keys");
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void equalsAReorderedCopyTenToTheFiveKeys() {
    // ADR-0044 as amended by #95: 2 955 565 bytes read, 2 887 ticks; .[1] steps one entry (1). Equality proves its left
    // side (10^5), finds the right side another entry, and takes it through its key index: the right side's proof
    // (10^5), then a lookup of each left key in its table, which charges none; then one tick per member pair (10^5).
    // 2 887 + 1 + 10^5 + 10^5 + 10^5 = 302 888 complete and 302 887 end. Before #95 each pair's field read scanned the
    // right side's 10^5 keys, n + n * m + n + m: the run ends in the second pair's scan.
    assertThat(WIDE_PAIR).as("[W,Wrev] of 10^5 keys, jq-ref's -c line without its LF").hasSize(2_955_565);
    assertGives(".[0] == .[1]", WIDE_PAIR, 302_888, "true", "[W,Wrev] of 10^5 keys");
    assertEnds(".[0] == .[1]", false, WIDE_PAIR, 302_887, "[W,Wrev] of 10^5 keys");
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void missesTheLastKeyTenToTheFiveKeys() {
    // ADR-0044 as amended by #95: the left side's last key, k99999, is the first one the right side lacks. Read 2 887,
    // .[1] 1, the left proof 10^5, the right side's proof 10^5, the lookups of k0 to k99999 in its table none (found or
    // not), then the 99 999 member pairs before the miss, in left key order, as jq compares (no size check first):
    // 2 887 + 1 + 2 * 10^5 + 99 999 = 302 887 complete and 302 886 end. Before #95 the run ends in the second pair's
    // scan of the right side's 10^5 keys.
    assertThat(WIDE_PAIR_RENAMED).as("[W,Wrev] with x99999 for k99999 on the right, jq-ref's -c line without its LF").hasSize(2_955_565);
    assertGives(".[0] == .[1]", WIDE_PAIR_RENAMED, 302_887, "false", "[W,Wrev] with x99999 on the right");
    assertEnds(".[0] == .[1]", false, WIDE_PAIR_RENAMED, 302_886, "[W,Wrev] with x99999 on the right");
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void collidingKeys() {
    // ADR-0044: the probe budget of 4 n runs out on keys that share a hash, and the proof sorts them, one tick per
    // compare (each key under 1 KiB). Read: 1 266 843 bytes, 1 238 ticks. Bound: read + 2 n + n * ceil(log2 n) =
    // 1 238 + 65 536 + 32 768 * 15 = 558 294. .[] takes the proof and one tick per member; -c . the proof and the
    // writer's 1 238 KiB, under n.
    assertThat(COLLIDING_OBJECT).as("the 2^15 colliding-key document, jq-ref's -c line without its LF").hasSize(1_266_843);
    assertAll(
        () -> assertIterates(COLLIDING_OBJECT, 558_294, indexes(COLLIDING), "2^15 colliding keys"),
        () -> assertPrints(COLLIDING_OBJECT, 558_294, line(COLLIDING_OBJECT), "2^15 colliding keys"));
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void equalsAReorderedCopyOfCollidingKeys() {
    // ADR-0044 as amended by #95, on keys that share one hash: 2 533 689 bytes read, 2 475 ticks; .[1] 1. Each side's
    // proof runs out of probes and sorts: n = 2^15 entries, the merge sort's compares, then n - 1 compares of neighbours.
    // Block b of a key is bit b of its index and the blocks compare first to last, so a merge at width w joins two runs
    // that differ only in bit log2 w, the last block that orders them: it interleaves them in 2w - 1 compares, 14 n + 1
    // over the 15 widths, in either order. Each proof is 16 n = 524 288 (measured before #95: .[0] | length takes
    // 2 475 + 16 n, .[1] | length 2 476 + 16 n, and collidingKeys' .[] exactly its 558 294). The right side's key index
    // then finds each left key by a binary search among its 2^15 sorted keys, one tick per compare; the ADR leaves the
    // search's shape open, so its count is bounded, not pinned. At least the depths of 2^15 nodes of a search tree, the
    // sum of floor(log2 i) + 1 for i = 1 to 2^15 = 458 769 (a midpoint search takes exactly that); then 2^15 member
    // pairs: 2 476 + 2 * 524 288 + 458 769 + 32 768 = 1 542 589, so 1 542 588 end. The completing bound is the
    // designer's, from the ADR's worst cases: read and .[1] 2 476, each proof n + n + n * 15 = 17 n, the lookups
    // n * ceil(log2(d + 1)) = 16 n, the pairs n: 1 673 644. Before #95 each pair's field read scans the right side's
    // 2^15 keys, and the run ends within 35 of them.
    assertThat(COLLIDING_PAIR).as("[C,Crev] of 2^15 colliding keys, jq-ref's -c line without its LF").hasSize(2_533_689);
    assertGives(".[0] == .[1]", COLLIDING_PAIR, 1_673_644, "true", "[C,Crev] of 2^15 colliding keys");
    assertEnds(".[0] == .[1]", false, COLLIDING_PAIR, 1_542_588, "[C,Crev] of 2^15 colliding keys");
  }
}
