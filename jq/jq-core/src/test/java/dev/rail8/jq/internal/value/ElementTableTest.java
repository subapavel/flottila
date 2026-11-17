package dev.rail8.jq.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.rail8.jq.JqEngine;
import dev.rail8.jq.JqTermination;
import dev.rail8.jq.Workspace;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * The element tables behind a filter's indexed reads of a read array. A read walks from the array's start and ticks
 * the elements it steps, as long as the walks of that array in the run have stepped no more elements than the array's
 * tape span. The read after that builds the array's table of element positions: one tick per element and four bytes
 * built per element. From then on a read and a length cost no tick. Every count here is exact.
 */
class ElementTableTest {

  private static final long UNLIMITED = Long.MAX_VALUE;

  /** {@code [0,1,…,n-1]}. */
  private static String counting(int n) {
    StringJoiner array = new StringJoiner(",", "[", "]");
    for (int i = 0; i < n; i++) array.add(Integer.toString(i));
    return array.toString();
  }

  /** The root array of {@code json} read into {@code workspace}. */
  private static ArrayView read(Workspace workspace, String json) {
    return (ArrayView) workspace.read(MemorySegment.ofArray(json.getBytes(UTF_8))).root();
  }

  /** A run state whose run has begun with every budget unlimited but its bytes built. */
  private static RunState running(long bytesBuilt) {
    RunState state = new RunState();
    state.begin(UNLIMITED, UNLIMITED, bytesBuilt, UNLIMITED);
    return state;
  }

  /** Reads elements 0 to 19 of {@code array} in order, checking each value. */
  private static void readTwenty(RunState state, ArrayView array) {
    for (int i = 0; i < 20; i++) {
      JqValue element = array.get(state, i);
      assertThat(element.toJson()).as("element %s", i).isEqualTo(Integer.toString(i));
    }
  }

  @Test
  void anIndexLoopBuildsTheTableOnceTheWalksPassTheSpan() {
    // The span of [0,…,19] is 20. Reads 0 to 6 walk 0 + 1 + … + 6 = 21 > 20, so read 7 builds the table: 20 ticks and
    // 4 x 20 = 80 bytes. Reads 7 to 19 then read the table: 21 + 20 = 41 ticks in all, against 190 for the walks alone.
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    ArrayView array = read(workspace, counting(20));
    RunState state = running(UNLIMITED);
    readTwenty(state, array);
    assertThat(state.steps()).as("ticks of reads 0 to 19").isEqualTo(41);
    assertThat(state.built()).as("bytes built for the table").isEqualTo(80);
    assertThat(array.length(state)).as("length from the table").isEqualTo(20);
    assertThat(array.get(state, 20)).as("a read past the end").isNull();
    assertThat(state.steps()).as("ticks after a length and a read past the end from the table").isEqualTo(41);

    // The next run starts with no table: the same reads cost the same.
    state.end();
    state.begin(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED);
    long before = state.steps();
    readTwenty(state, array);
    assertThat(state.steps() - before).as("ticks of the same reads in the next run").isEqualTo(41);
  }

  @Test
  void aViewOfALaterReadIsNeverAnsweredFromAnEarlierTable() {
    // The tape is reused by the next read, so the later array sits at the same tape index as the earlier one. A read of
    // the earlier view is stale even though its table is built, and the later array is a new entry.
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    ArrayView earlier = read(workspace, counting(20));
    RunState state = running(UNLIMITED);
    readTwenty(state, earlier);
    ArrayView later = read(workspace, "[7,8,9]");
    assertThatExceptionOfType(JqTermination.class)
        .as("a read of the earlier view after the later read")
        .isThrownBy(() -> earlier.get(state, 3))
        .satisfies(t -> assertThat(t.cause()).as("cause").isEqualTo(JqTermination.Cause.STALE_VIEW));
    long before = state.steps();
    assertThat(later.get(state, 2).toJson()).as("element 2 of the later array").isEqualTo("9");
    assertThat(state.steps() - before).as("ticks of the later read: a walk of 2").isEqualTo(2);
  }

  @Test
  void aTableThatDoesNotFitLeavesTheWalks() {
    // 79 bytes cannot hold the table of 20 elements (80): every read walks as without a table, 0 + 1 + … + 19 = 190
    // ticks, and the run neither ends nor builds.
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    ArrayView array = read(workspace, counting(20));
    RunState state = running(79);
    readTwenty(state, array);
    assertThat(state.steps()).as("ticks of reads 0 to 19 with no table").isEqualTo(190);
    assertThat(state.built()).as("bytes built with no table").isZero();

    // Witness: with 80 bytes the table is built.
    RunState fits = running(80);
    readTwenty(fits, array);
    assertThat(fits.steps()).as("ticks of reads 0 to 19 with 80 bytes").isEqualTo(41);
  }

  /** The elements of the root array of {@code json}, read into {@code workspace}, as views. */
  private static ArrayView[] elements(Workspace workspace, String json) {
    ArrayView root = read(workspace, json);
    RunState free = new RunState();
    ArrayView[] elements = new ArrayView[root.length()];
    for (int k = 0; k < elements.length; k++) elements[k] = (ArrayView) root.get(free, k);
    return elements;
  }

  /** {@code [a,b,…]} of the given arrays. */
  private static String arrayOf(String... arrays) {
    return "[" + String.join(",", arrays) + "]";
  }

  @Test
  void aHotArrayKeepsItsTableAmongOneShotReads() {
    // Each read of the hot array is followed by reads of four single-element arrays, each read once. They evict each
    // other, never the hot array, whose walks outweigh theirs, so it builds its table at read 7 as alone: 41 ticks, and
    // 0 for each single read. Replacing the least recently used entry instead would evict the hot array at every read:
    // 190.
    String[] arrays = new String[81];
    arrays[0] = counting(20);
    Arrays.fill(arrays, 1, 81, "[0]");
    ArrayView[] views = elements(JqEngine.builder().build().newWorkspace(), arrayOf(arrays));
    RunState state = running(UNLIMITED);
    for (int i = 0; i < 20; i++) {
      assertThat(views[0].get(state, i).toJson()).as("element %s of the hot array", i).isEqualTo(Integer.toString(i));
      for (int k = 1; k <= 4; k++) {
        assertThat(views[4 * i + k].get(state, 0).toJson()).as("the single element of array %s", 4 * i + k).isEqualTo("0");
      }
    }
    assertThat(state.steps()).as("ticks of the hot reads and the 80 single reads").isEqualTo(41);
  }

  @Test
  void anEntryWithATableIsReplacedAfterTheOnesWithout() {
    // [0,…,19] builds its table (41 ticks); three arrays of 1000 elements are read at 500 (1500), which outweighs the
    // 21 elements the walks of the first array stepped; a fifth array then replaces one of the three, not the one with
    // the table, whose next read costs nothing: 1541.
    String thousand = counting(1000);
    ArrayView[] views = elements(JqEngine.builder().build().newWorkspace(), arrayOf(counting(20), thousand, thousand, thousand, "[0]"));
    RunState state = running(UNLIMITED);
    readTwenty(state, views[0]);
    for (int k = 1; k <= 3; k++) {
      assertThat(views[k].get(state, 500).toJson()).as("element 500 of array %s", k).isEqualTo("500");
    }
    assertThat(views[4].get(state, 0).toJson()).as("the single element").isEqualTo("0");
    assertThat(views[0].get(state, 5).toJson()).as("element 5 from the table").isEqualTo("5");
    assertThat(state.steps()).as("ticks of the table, three walks of 500 and two reads").isEqualTo(1541);
  }

  @Test
  void anEntryNotReadInTheLastAgeReadsIsReplacedFirst() {
    // Three arrays of 1000 elements are read at 999 (2997) and never again. Then each read of a hot array of 100 elements
    // is followed by a read of a single-element array. While the three keep their slots, outweighing the hot array, the
    // hot array and the single read replace each other in the fourth slot, and each hot read walks from a count of zero:
    // reads 0 to 29, 435. Read 30 of the hot array is the memo's 64th read; at the next one the first of the three has
    // not been read in 64 reads and is replaced, then the other two. The hot array keeps its slot from read 30 on: its
    // walks 30 + 31 + 32 + 33 = 126 pass its span of 100, read 34 builds the table (100), and the later reads cost none:
    // 2997 + 435 + 126 + 100 = 3658, against 2997 + 4950 if the three kept their slots.
    String thousand = counting(1000);
    String[] arrays = new String[104];
    arrays[0] = thousand;
    arrays[1] = thousand;
    arrays[2] = thousand;
    arrays[3] = counting(100);
    Arrays.fill(arrays, 4, 104, "[0]");
    ArrayView[] views = elements(JqEngine.builder().build().newWorkspace(), arrayOf(arrays));
    RunState state = running(UNLIMITED);
    for (int k = 0; k < 3; k++) {
      assertThat(views[k].get(state, 999).toJson()).as("element 999 of array %s", k).isEqualTo("999");
    }
    for (int i = 0; i < 100; i++) {
      assertThat(views[3].get(state, i).toJson()).as("element %s of the hot array", i).isEqualTo(Integer.toString(i));
      assertThat(views[4 + i].get(state, 0).toJson()).as("the single element of array %s", 4 + i).isEqualTo("0");
    }
    assertThat(ElementTable.AGE).as("the age the counts above assume").isEqualTo(64);
    assertThat(state.steps()).as("ticks of the three walks, the hot reads and the single reads").isEqualTo(3658);
  }

  @Test
  void aLengthCountsTowardTheTableButEntersNoArray() {
    // A length walks and ticks 20 and enters nothing: the reads after it count from zero, 41 as alone.
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    ArrayView array = read(workspace, counting(20));
    RunState state = running(UNLIMITED);
    assertThat(array.length(state)).as("length").isEqualTo(20);
    readTwenty(state, array);
    assertThat(state.steps()).as("ticks of a length and reads 0 to 19").isEqualTo(20 + 41);

    // Of an entered array, a length's walk counts: read 0 (0 ticks), two lengths (40, the walks now 40 > 20), then
    // the next read builds (20) and reads 0 to 19 cost nothing more.
    RunState entered = running(UNLIMITED);
    assertThat(array.get(entered, 0).toJson()).as("element 0").isEqualTo("0");
    array.length(entered);
    array.length(entered);
    readTwenty(entered, array);
    assertThat(entered.steps()).as("ticks of read 0, two lengths and reads 0 to 19").isEqualTo(60);
  }

  @Test
  void aTableNeedsItsElementsWithinTheCapAndItsBytesWithinTheBudget() {
    RunState unlimited = running(UNLIMITED);
    assertThat(ElementTable.fits(unlimited, ElementTable.CAP)).as("the cap's elements").isTrue();
    assertThat(ElementTable.fits(unlimited, ElementTable.CAP + 1)).as("one element past the cap").isFalse();
    RunState eighty = running(80);
    assertThat(ElementTable.fits(eighty, 20)).as("20 elements in 80 bytes").isTrue();
    assertThat(ElementTable.fits(eighty, 21)).as("21 elements in 80 bytes").isFalse();
  }
}
