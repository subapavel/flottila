package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.query.run.RunState;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The edges of ADR-0044's key proof that {@link KeyProofTest} leaves open: a key of exactly one KiB, eight colliding
 * keys, a probe budget spent to exactly zero, a budget that runs out at a free slot, keys that lie far into their
 * document, and keys of one 31-polynomial or of polynomials that agree in the table's mask. A tick charges a key's length, never where it lies, so a document behind
 * 4 KiB of white space ticks as the same document without it. Keys and clusters are found as KeyProofTest finds them,
 * against the proof's own {@link KeyProof#hash} in a table of 64 slots.
 */
class KeyProofEdgesTest {
  /** The table's mask for 9 to 16 key entries: 64 slots. */
  private static final int MASK = 63;

  /** White space that puts every key of the document after it past 4 KiB. */
  private static final String FAR = " ".repeat(4096);

  /** 2 045 bytes, so a key of it and a five-digit number is 2 050 bytes, 3 KiB. */
  private static final int LONG = 2045;

  private static ObjectView object(String json) {
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    return (ObjectView) workspace.read(MemorySegment.ofArray(json.getBytes(US_ASCII))).root();
  }

  /** {@code {"k0":0,…}}: the keys in the order given, each value its place. */
  private static String json(List<String> keys) {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = 0; i < keys.size(); i++) object.add("\"" + keys.get(i) + "\":" + i);
    return object.toString();
  }

  private static int home(String key) {
    byte[] bytes = key.getBytes(US_ASCII);
    return KeyProof.hash(bytes, 0, bytes.length) & MASK;
  }

  /** The first {@code count} keys {@code prefix} + a five-digit number whose home slot is 0. */
  private static List<String> cluster(String prefix, int count) {
    List<String> keys = new ArrayList<>();
    for (int i = 0; keys.size() < count; i++) {
      String key = prefix + String.format("%05d", i);
      if (home(key) == 0) keys.add(key);
    }
    return keys;
  }

  /** Three keys {@code o} + a five-digit number whose home slots differ and lie in the table's upper half. */
  private static List<String> others() {
    List<String> keys = new ArrayList<>();
    List<Integer> homes = new ArrayList<>();
    for (int i = 0; keys.size() < 3; i++) {
      String key = "o" + String.format("%05d", i);
      int home = home(key);
      if (home >= 32 && !homes.contains(home)) {
        keys.add(key);
        homes.add(home);
      }
    }
    return keys;
  }

  private static List<String> sorted(List<String> a, List<String> b) {
    return Stream.concat(a.stream(), b.stream()).sorted().toList();
  }

  /** The 16 keys of four Aa/BB blocks: one 31-polynomial, so one hash, and a proof of them sorts. */
  private static List<String> blocks16() {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      StringBuilder key = new StringBuilder();
      for (int b = 0; b < 4; b++) key.append((i >> b & 1) == 1 ? "BB" : "Aa");
      keys.add(key.toString());
    }
    return keys;
  }

  private static long proofTicks(ObjectView object) {
    RunState state = new RunState();
    KeyProof.walk(state, object, false);
    return state.steps();
  }

  private static ObjectWalk walkOf(String json) {
    return KeyProof.walk(new RunState(), object(json), false);
  }

  private static BuiltObject built(String key) {
    return BuiltObject.ofPairs(new Object[] {BuiltString.of(key), BuiltString.of("v")});
  }

  @Test
  void aKeyOfOneKiBTicksOnceAndOneByteMoreTwice() {
    assertThat(proofTicks(object("{\"" + "x".repeat(1024) + "\":0}"))).as("entry ticks of a 1 024-byte key").isEqualTo(1);
    assertThat(proofTicks(object("{\"" + "x".repeat(1025) + "\":0}"))).as("entry ticks of a 1 025-byte key").isEqualTo(2);
  }

  @Test
  void anIndexOfEightCollidingKeysStillComparesPairwise() {
    // 8 keys of one hash would probe 36 slots of a table's 32-probe budget and sort; pairwise, the index charges only
    // its 8 entry ticks.
    List<String> keys = blocks16().subList(0, 8);
    RunState state = new RunState();
    KeyIndex index = KeyProof.index(state, object(json(keys)), walkOf(json(keys)));
    assertThat(index.size()).as("distinct keys of 8 colliding keys").isEqualTo(8);
    assertThat(state.steps()).as("ticks of the pairwise index of 8 colliding keys").isEqualTo(8);
  }

  @Test
  void aRepeatWhoseLastProbeSpendsTheBudgetToZeroStaysInTheTable() {
    // 10 entries, a budget of 40: an other key 1, a cluster of 8 at one home slot 1 + 2 + ... + 8 = 36, then the
    // cluster's third key again, 3 compares: 40, the whole budget, so the table proves them, 10 entry ticks.
    List<String> cluster = cluster("c", 8);
    List<String> keys = new ArrayList<>();
    keys.add(others().getFirst());
    keys.addAll(cluster);
    keys.add(cluster.get(2));
    ObjectView object = object(json(keys));
    assertThat(proofTicks(object)).as("ticks of a table proof that spends its budget to zero").isEqualTo(10);
    assertThat(KeyProof.walk(new RunState(), object, false).size()).as("distinct keys with one repeat").isEqualTo(9);
    // The cluster's fourth key C4 again: 4 compares, one past the budget, so the proof sorts [o, C1..C8, C4], each
    // compare of two short keys 1. The merges: width 1, 5; width 2, [C1, o] with [C2, C3] 3 and [C4, C5] with
    // [C6, C7] 2; width 4, 3 + 4 = 7; width 8, [C1..C7, o] with [C4, C8] 9: 26. The neighbours: 8 distinct keys
    // after the first, one compare each, and C4 with its repeat, 9. With the 10 entry ticks, 45.
    List<String> past = new ArrayList<>(keys.subList(0, 9));
    past.add(cluster.get(3));
    assertThat(proofTicks(object(json(past)))).as("ticks of a proof one probe past its budget").isEqualTo(10 + 26 + 9);
  }

  @Test
  void aBudgetThatRunsOutAtAFreeSlotSortsAndKeepsTheRepeat() {
    // The cluster of 8, 36 probes, its fourth key again, 4: the budget of 40 is spent, so the other key's free slot is one
    // probe past it and the proof sorts. The repeat keeps its first place with its last value.
    List<String> cluster = cluster("c", 8);
    List<String> keys = new ArrayList<>(cluster);
    keys.add(cluster.get(3));
    keys.add(others().getFirst());
    ObjectWalk walk = KeyProof.walk(new RunState(), object(json(keys)), false);
    assertThat(walk.size()).as("distinct keys of 10 entries with one repeat, sorted").isEqualTo(9);
    List<String> members = new ArrayList<>();
    RunState free = new RunState();
    for (int c = walk.first(); c != -1; c = walk.next(free, c)) members.add(walk.key(c).value() + "=" + walk.member(free, c).toJson());
    assertThat(members).as("the repeat at its first place with its last value").element(3).isEqualTo(cluster.get(3) + "=8");
  }

  @Test
  void aSortCompareOfAFarShortKeyWithALongOneChargesTheShortOne() {
    // KeyProofTest's 9 long colliding keys and 3 others, sorted with the others first, behind 4 KiB: the same
    // 30 + 52 + 27 ticks as without the white space, since a compare charges the shorter key's KiB.
    ObjectView far = object(FAR + json(sorted(cluster("x".repeat(LONG), 9), others())));
    assertThat(proofTicks(far)).as("ticks of the sort of far keys, the short ones first").isEqualTo(30 + 52 + 27);
  }

  @Test
  void aSortCompareOfALongKeyWithAFarShortOneChargesTheShortOne() {
    // 9 long colliding keys of a's, so they sort before the 3 others, behind 4 KiB. Entry ticks 9 * 3 + 3 = 30. The
    // merges of the sorted layout compare each element of the left run once: width 1, four long pairs at 3 and two
    // with an other at 1, 14; width 2, two long pairs of runs at 2 * 3 and [L8, o0] with [o1, o2] at 2, 14; width 4,
    // 4 * 3 = 12; width 8, eight long keys with L8, 24: 64. The neighbours: 8 long pairs, 24, and 3 with an other, 27.
    ObjectView far = object(FAR + json(sorted(cluster("a".repeat(LONG), 9), others())));
    assertThat(proofTicks(far)).as("ticks of the sort of far keys, the long ones first").isEqualTo(30 + 64 + 27);
  }

  @Test
  void aSortedLookupOfAFarShortLeftKeyChargesItsOwnLength() {
    // KeyProofTest's 16 keys of 3 KiB, and a left object behind 4 KiB whose short key sorts before them all: 4
    // compares at 1 each, as for the same key built.
    List<String> keys = new ArrayList<>();
    for (String key : blocks16()) keys.add("x".repeat(LONG) + key);
    ObjectView right = object(json(keys));
    long proof = proofTicks(right);
    RunState state = new RunState();
    KeyProof.index(state, right, walkOf(FAR + "{\"AaAaAaAB\":0}"));
    assertThat(state.steps()).as("ticks of a far short left key among 3 KiB keys").isEqualTo(proof + 4);
  }

  @Test
  void aSortedLookupOfALongLeftKeyAmongFarShortKeysChargesTheirLength() {
    // The 16 short keys behind 4 KiB, and a built key of 2 053 bytes that sorts before them all: 4 compares at 1 each.
    ObjectView right = object(FAR + json(blocks16()));
    long proof = proofTicks(right);
    RunState state = new RunState();
    KeyProof.index(state, right, built("AaAaAaA" + "A".repeat(2046)));
    assertThat(state.steps()).as("ticks of a 3 KiB left key among far short keys").isEqualTo(proof + 4);
  }

  @Test
  void keysOfOneThirtyOnePolynomialReachTheSort() {
    // The 16 Aa/BB keys share one 31-polynomial, so one hash: 136 probes past the budget of 64. In ascending order a
    // merge compares each element of its left run once, 8 per pass of 4 passes, and the 15 neighbours once each: 16
    // entry ticks, 32 and 15.
    ObjectView object = object(json(blocks16().stream().sorted().toList()));
    assertThat(proofTicks(object)).as("ticks of the sorted proof of 16 keys of one polynomial").isEqualTo(16 + 32 + 15);
  }

  @Test
  void keysWhosePolynomialsDifferOnlyAboveTheSlotMaskStayInTheTable() {
    // AA, CC, EE, ... __: each step adds 2 to both bytes, 31 * 2 + 2 = 64 to the polynomial, so the 16 keys agree in its
    // 6 low bits, the table's mask. The finaliser spreads the high bits into the home slot, so the table proves them
    // within its budget, one tick per key entry.
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 16; i++) keys.add(String.valueOf((char) ('A' + 2 * i)).repeat(2));
    assertThat(proofTicks(object(json(keys)))).as("ticks of the table proof of 16 keys one polynomial mask apart").isEqualTo(16);
  }
}
