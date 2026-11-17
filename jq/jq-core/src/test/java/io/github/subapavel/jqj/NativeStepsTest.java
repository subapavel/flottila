package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RangeStepsTest.completes;
import static io.github.subapavel.jqj.RangeStepsTest.ends;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The ticks of #72's natives (ADR-0008, ADR-0034), each with an adjacent pair as in {@link RangeStepsTest}: N steps
 * complete, N - 1 end. Every run is on {@code null} given as a value, so no read ticks; a constant array of n elements
 * costs n - 1 steps of its own (its commas), a constant object and a string repeat none. The results are jq-ref
 * 1.8.2's, observed 2026-11-13 (diff/natives.rec and diff/index.rec for the same shapes).
 */
class NativeStepsTest {

  /** length ticks once per started KiB of a string and not at all for a built array. */
  @Test
  void lengthTicksPerKibibyteOfAString() {
    assertThat(completes("\"a\" * 1024 | length", 1)).as("results of a KiB's length with 1 step").containsExactly("1024");
    assertThat(ends("\"a\" * 1024 | length", 0)).as("results of a KiB's length with 0 steps").isEmpty();
    assertThat(completes("\"a\" * 1025 | length", 2)).as("results of 1025 bytes' length with 2 steps").containsExactly("1025");
    assertThat(ends("\"a\" * 1025 | length", 1)).as("results of 1025 bytes' length with 1 step").isEmpty();
    assertThat(completes("[1,2,3] | length", 2)).as("results of [1,2,3] | length with its 2 steps").containsExactly("3");
  }

  /** tonumber ticks once per started KiB of its string. */
  @Test
  void tonumberTicksPerKibibyteOfItsString() {
    assertThat(completes("\"12\" | tonumber", 1)).as("results of \"12\" | tonumber with 1 step").containsExactly("12");
    assertThat(ends("\"12\" | tonumber", 0)).as("results of \"12\" | tonumber with 0 steps").isEmpty();
    assertThat(completes("\"1\" * 1025 | tonumber", 2)).as("results of 1025 digits' tonumber with 2 steps").hasSize(1);
    assertThat(ends("\"1\" * 1025 | tonumber", 1)).as("results of 1025 digits' tonumber with 1 step").isEmpty();
  }

  /** tojson and tostring tick once per started KiB of the text they write, past the array's 1 step. */
  @Test
  void tojsonAndTostringTickPerKibibyteOfTheirText() {
    assertThat(completes("[1,[2]] | tojson", 2)).as("results of tojson with 2 steps").containsExactly("\"[1,[2]]\"");
    assertThat(ends("[1,[2]] | tojson", 1)).as("results of tojson with 1 step").isEmpty();
    assertThat(completes("[1,[2]] | tostring", 2)).as("results of tostring with 2 steps").containsExactly("\"[1,[2]]\"");
    assertThat(ends("[1,[2]] | tostring", 1)).as("results of tostring with 1 step").isEmpty();
  }

  /**
   * keys_unsorted ticks once per key; keys once more per compare of its sort, the compares of jqj's bottom-up merge sort
   * (F-48): two keys one, three two, four in order four (TimSort makes three), eight as {@code dhbfaegc} seventeen
   * (TimSort makes eighteen).
   */
  @Test
  void keysTickPerKeyAndPerCompare() {
    assertThat(completes("{\"b\":1,\"a\":2} | keys_unsorted", 2)).as("keys_unsorted with 2 steps").containsExactly("[\"b\",\"a\"]");
    assertThat(ends("{\"b\":1,\"a\":2} | keys_unsorted", 1)).as("keys_unsorted with 1 step").isEmpty();
    assertThat(completes("{\"b\":1,\"a\":2} | keys", 3)).as("keys of two with 3 steps").containsExactly("[\"a\",\"b\"]");
    assertThat(ends("{\"b\":1,\"a\":2} | keys", 2)).as("keys of two with 2 steps").isEmpty();
    assertThat(completes("{\"c\":1,\"b\":2,\"a\":3} | keys", 5)).as("keys of three with 5 steps").containsExactly("[\"a\",\"b\",\"c\"]");
    assertThat(ends("{\"c\":1,\"b\":2,\"a\":3} | keys", 4)).as("keys of three with 4 steps").isEmpty();
    String four = "{\"a\":0,\"b\":0,\"c\":0,\"d\":0} | keys";
    assertThat(completes(four, 8)).as("keys of four in order with 8 steps").containsExactly("[\"a\",\"b\",\"c\",\"d\"]");
    assertThat(ends(four, 7)).as("keys of four in order with 7 steps").isEmpty();
    String eight = "{\"d\":0,\"h\":0,\"b\":0,\"f\":0,\"a\":0,\"e\":0,\"g\":0,\"c\":0} | keys";
    assertThat(completes(eight, 25))
        .as("keys of eight with 25 steps")
        .containsExactly("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\"]");
    assertThat(ends(eight, 24)).as("keys of eight with 24 steps").isEmpty();
  }

  /** An array's keys tick once per index, past the array's 2 steps. */
  @Test
  void anArraysKeysTickPerIndex() {
    assertThat(completes("[1,2,3] | keys", 5)).as("keys of [1,2,3] with 5 steps").containsExactly("[0,1,2]");
    assertThat(ends("[1,2,3] | keys", 4)).as("keys of [1,2,3] with 4 steps").isEmpty();
  }

  /** has on a built object ticks as its lookup does: once. */
  @Test
  void hasTicksAsItsLookup() {
    assertThat(completes("{\"a\":1} | has(\"a\")", 1)).as("has with 1 step").containsExactly("true");
    assertThat(ends("{\"a\":1} | has(\"a\")", 0)).as("has with 0 steps").isEmpty();
  }

  /** flatten ticks once per member it visits at every level, past the array's 2 steps: five, or four to depth 1. */
  @Test
  void flattenTicksPerMemberAtEveryLevel() {
    assertThat(completes("[1,[2,[3]]] | flatten", 7)).as("flatten with 7 steps").containsExactly("[1,2,3]");
    assertThat(ends("[1,[2,[3]]] | flatten", 6)).as("flatten with 6 steps").isEmpty();
    assertThat(completes("[1,[2,[3]]] | flatten(1)", 6)).as("flatten(1) with 6 steps").containsExactly("[1,2,[3]]");
    assertThat(ends("[1,[2,[3]]] | flatten(1)", 5)).as("flatten(1) with 5 steps").isEmpty();
  }

  /** An array key on an array ticks once per pair it compares: every place of the input times every key element. */
  @Test
  void anArrayKeyTicksPerPair() {
    assertThat(completes("[1,2,1] | .[[1]]", 5)).as(".[[1]] with 5 steps").containsExactly("[0,2]");
    assertThat(ends("[1,2,1] | .[[1]]", 4)).as(".[[1]] with 4 steps").isEmpty();
    assertThat(completes("[1,2,1,2] | .[[1,2]]", 12)).as(".[[1,2]] with 12 steps").containsExactly("[0,2]");
    assertThat(ends("[1,2,1,2] | .[[1,2]]", 11)).as(".[[1,2]] with 11 steps").isEmpty();
  }

  /** last/1 adds no tick to its generator's (the comma's 2); the constant natives tick nothing. */
  @Test
  void lastAndTheConstantNativesAddNoTicks() {
    assertThat(completes("last(1,2,3)", 2)).as("last with 2 steps").containsExactly("3");
    assertThat(ends("last(1,2,3)", 1)).as("last with 1 step").isEmpty();
    assertThat(completes("[nan | isnan, (infinite | isinfinite), (1 | isnormal), have_decnum, have_literal_numbers, (1 | type)]", 5))
        .as("the constant natives with the array's 5 steps")
        .containsExactly("[true,true,true,true,true,\"number\"]");
  }
}
