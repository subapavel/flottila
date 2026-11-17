package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RangeStepsTest.completes;
import static io.github.subapavel.jqj.RangeStepsTest.ends;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The ticks of #73's natives (ADR-0008, ADR-0034, ADR-0037), each with an adjacent pair as in {@link RangeStepsTest}: N
 * steps complete, N - 1 end. Each ticks once per member it reads and adds the ticks of the compares and equality checks
 * it makes, never one of its own per compare. Every run is on {@code null} given as a value, so no read ticks; a
 * constant array of n elements costs n - 1 steps of its own (its commas), and a compare or equality check of two numbers
 * none. The results are jq-ref 1.8.2's, observed 2026-11-13 (diff/sort.rec for the same shapes).
 */
class SortStepsTest {

  /** sort, unique, min and max tick once per member: three, past the array's 2 steps. */
  @Test
  void theFormsWithoutKeysTickOncePerMember() {
    assertThat(completes("[3,1,2] | sort", 5)).as("sort with 5 steps").containsExactly("[1,2,3]");
    assertThat(ends("[3,1,2] | sort", 4)).as("sort with 4 steps").isEmpty();
    assertThat(completes("[3,1,3] | unique", 5)).as("unique with 5 steps").containsExactly("[1,3]");
    assertThat(ends("[3,1,3] | unique", 4)).as("unique with 4 steps").isEmpty();
    assertThat(completes("[3,1,2] | min", 5)).as("min with 5 steps").containsExactly("1");
    assertThat(ends("[3,1,2] | min", 4)).as("min with 4 steps").isEmpty();
    assertThat(completes("[3,1,2] | max", 5)).as("max with 5 steps").containsExactly("3");
    assertThat(ends("[3,1,2] | max", 4)).as("max with 4 steps").isEmpty();
  }

  /** The count does not grow with the compares a sort makes: five members in reverse cost five, past the 4 commas. */
  @Test
  void aSortsComparesAreNotTicked() {
    assertThat(completes("[5,4,3,2,1] | sort", 9)).as("sort of five with 9 steps").containsExactly("[1,2,3,4,5]");
    assertThat(ends("[5,4,3,2,1] | sort", 8)).as("sort of five with 8 steps").isEmpty();
  }

  /** The _impl forms tick once per member, past the 2 steps of the input and the 2 of the keys. */
  @Test
  void theImplFormsTickOncePerMember() {
    assertThat(completes("[3,1,2] | _sort_by_impl([2,0,1])", 7)).as("_sort_by_impl with 7 steps").containsExactly("[1,2,3]");
    assertThat(ends("[3,1,2] | _sort_by_impl([2,0,1])", 6)).as("_sort_by_impl with 6 steps").isEmpty();
    assertThat(completes("[3,1,2] | _unique_by_impl([0,0,1])", 7)).as("_unique_by_impl with 7 steps").containsExactly("[3,2]");
    assertThat(ends("[3,1,2] | _unique_by_impl([0,0,1])", 6)).as("_unique_by_impl with 6 steps").isEmpty();
    assertThat(completes("[3,1,2] | _group_by_impl([0,0,1])", 7)).as("_group_by_impl with 7 steps").containsExactly("[[3,1],[2]]");
    assertThat(ends("[3,1,2] | _group_by_impl([0,0,1])", 6)).as("_group_by_impl with 6 steps").isEmpty();
    assertThat(completes("[3,1,2] | _min_by_impl([2,0,1])", 7)).as("_min_by_impl with 7 steps").containsExactly("1");
    assertThat(ends("[3,1,2] | _min_by_impl([2,0,1])", 6)).as("_min_by_impl with 6 steps").isEmpty();
    assertThat(completes("[3,1,2] | _max_by_impl([2,0,1])", 7)).as("_max_by_impl with 7 steps").containsExactly("3");
    assertThat(ends("[3,1,2] | _max_by_impl([2,0,1])", 6)).as("_max_by_impl with 6 steps").isEmpty();
  }

  /**
   * Array keys add their compares' and equality checks' ticks, one per member pair: the merge sort compares [1] with
   * [1], then [1] with [2] twice, and the groups check [1] against [1] and [2], so 3 and 2 past the 7 of the members.
   */
  @Test
  void keysAddTheTicksOfTheirComparesAndEqualityChecks() {
    assertThat(completes("[3,1,2] | _group_by_impl([[1],[1],[2]])", 12)).as("_group_by_impl with 12 steps").containsExactly("[[3,1],[2]]");
    assertThat(ends("[3,1,2] | _group_by_impl([[1],[1],[2]])", 11)).as("_group_by_impl with 11 steps").isEmpty();
  }

  /** An empty array costs nothing. */
  @Test
  void anEmptyArrayCostsNothing() {
    assertThat(completes("[] | sort", 0)).as("sort of [] with 0 steps").containsExactly("[]");
    assertThat(completes("[] | unique", 0)).as("unique of [] with 0 steps").containsExactly("[]");
    assertThat(completes("[] | min", 0)).as("min of [] with 0 steps").containsExactly("null");
    assertThat(completes("[] | max", 0)).as("max of [] with 0 steps").containsExactly("null");
  }
}
