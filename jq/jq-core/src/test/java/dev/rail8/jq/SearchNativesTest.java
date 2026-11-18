package dev.rail8.jq;

import static dev.rail8.jq.RangeStepsTest.completes;
import static dev.rail8.jq.RangeStepsTest.ends;
import static dev.rail8.jq.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The natives {@code contains/1}, {@code bsearch/1} and {@code builtins/0}, and the prelude's {@code inside/1} over
 * {@code contains}: their results and errors on edges that jq's own test files leave out, and their ticks, each with an
 * adjacent pair as in {@link RangeStepsTest}: N steps complete, N - 1 end. Every run is on {@code null}; a constant
 * string, a string repeat of constants included, costs no step, a constant array of n elements n - 1. The expected
 * values are jq-ref 1.8.2's, observed with {@code jq-ref -nc}.
 */
class SearchNativesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void containsFindsSubstringsByTheirBytes() {
    assertThat(results("[\"abé\", \"\", \"a\"] | map(contains(\"é\"), contains(\"\"), contains(\"ab\"), contains(\"ba\"))"))
        .as("contains on multibyte, empty and short strings")
        .containsExactly("[true,true,true,false,false,true,false,false,false,true,false,false]");
  }

  @Test
  void arrayContainsTriesTheInputsElementsInOrderForEachElement() {
    assertThat(results("[[1, 2], [3, 4]] | contains([[4], [1]]), contains([[2, 3]]), contains([[]]), contains([])"))
        .as("each element of the argument in some element of the input")
        .containsExactly("true", "false", "true", "true");
    assertThat(results("[] | contains([1]), contains([])")).as("an empty input").containsExactly("false", "true");
    assertThat(results("[[1, 0], [0]] | contains([[0], [0, 1]])")).as("a miss on the first element, then a hit").containsExactly("true");
  }

  @Test
  void objectContainsComparesKeyByKey() {
    assertThat(results("{\"a\": \"xyz\", \"b\": [1, 2], \"n\": null} | contains({\"a\": \"y\", \"b\": [2]}), contains({\"n\": null}), contains({\"m\": null}), contains({}), contains({\"b\": [3]})"))
        .as("contains on objects")
        .containsExactly("true", "true", "false", "true", "false");
  }

  @Test
  void scalarsAreContainedWhenEqual() {
    assertThat(results("(1.0 | contains(1)), (1 | contains(2)), (null | contains(null)), (true | contains(true)), (nan | contains(nan)), ([nan] | contains([nan]))"))
        .as("contains on numbers, null, true and NaN")
        .containsExactly("true", "false", "true", "true", "false", "false");
  }

  @Test
  void twoKindsCannotHaveTheirContainmentChecked() {
    assertThat(results("try (true | contains(false)) catch ., try (1 | contains(\"a\")) catch ., try ({} | contains([])) catch ., try ([1] | contains([true, false])) catch ."))
        .as("contains of two kinds, true and false being two; inside, only at the top")
        .containsExactly(
            "\"boolean (true) and boolean (false) cannot have their containment checked\"",
            "\"number (1) and string (\\\"a\\\") cannot have their containment checked\"",
            "\"object ({}) and array ([]) cannot have their containment checked\"",
            "false");
  }

  @Test
  void insideIsContainsTheOtherWay() {
    assertThat(results("([1, 2] | inside([1, 2, 3])), (\"b\" | inside(\"abc\")), ({\"a\": 1} | inside({})), try (1 | inside(\"a\")) catch ."))
        .as("inside")
        .containsExactly("true", "true", "false", "\"string (\\\"a\\\") and number (1) cannot have their containment checked\"");
  }

  @Test
  void stringContainsTicksAtTheSplitRate() {
    assertThat(completes("\"a\" * 1024 | contains(\"a\")", 1)).as("contains in a KiB with 1 step").containsExactly("true");
    assertThat(ends("\"a\" * 1024 | contains(\"a\")", 0)).as("contains in a KiB with 0 steps").isEmpty();
    assertThat(completes("\"a\" * 2048 | contains(\"b\" * 1025)", 4)).as("2 KiB times 2 KiB with 4 steps").containsExactly("false");
    assertThat(ends("\"a\" * 2048 | contains(\"b\" * 1025)", 3)).as("2 KiB times 2 KiB with 3 steps").isEmpty();
  }

  @Test
  void arrayContainsTicksOncePerPair() {
    // Each constant array costs its comma; the pairs are (1, 2) and (2, 2), then also (1, 3) and (2, 3).
    assertThat(completes("[1, 2] | contains([2])", 3)).as("two pairs with 3 steps").containsExactly("true");
    assertThat(ends("[1, 2] | contains([2])", 2)).as("two pairs with 2 steps").isEmpty();
    assertThat(completes("[1, 2] | contains([2, 3])", 6)).as("four pairs with 6 steps").containsExactly("false");
    assertThat(ends("[1, 2] | contains([2, 3])", 5)).as("four pairs with 5 steps").isEmpty();
  }

  @Test
  void objectContainsTicksOncePerPairAndPerKeyLookedUp() {
    // The constant array costs its comma, the lookup of a in the built input one key compared; then the pair of the
    // values of a, and the pairs (1, 2) and (2, 2) one level deeper.
    assertThat(completes("{\"a\": [1, 2]} | contains({\"a\": [2]})", 5)).as("three pairs with 5 steps").containsExactly("true");
    assertThat(ends("{\"a\": [1, 2]} | contains({\"a\": [2]})", 4)).as("three pairs with 4 steps").isEmpty();
    // Two lookups, of a past one key and of b past two, and two pairs.
    assertThat(completes("{\"a\": 1, \"b\": 2} | contains({\"a\": 1, \"b\": 2})", 5)).as("two keys with 5 steps").containsExactly("true");
    assertThat(ends("{\"a\": 1, \"b\": 2} | contains({\"a\": 1, \"b\": 2})", 4)).as("two keys with 4 steps").isEmpty();
  }

  @Test
  void bsearchGivesTheIndexOrTheInsertionPoint() {
    assertThat(results("[1, 2, 3] | bsearch(2), bsearch(0), bsearch(4), bsearch(2.5), bsearch(1), bsearch(3)"))
        .as("bsearch in [1,2,3]")
        .containsExactly("1", "-1", "-4", "-3", "0", "2");
    assertThat(results("[] | bsearch(1)")).as("bsearch in []").containsExactly("-1");
    assertThat(results("[null, false, true, 1, \"a\", [1], {\"a\": 1}] | bsearch(true), bsearch({}), bsearch(-1)"))
        .as("bsearch in jq's value order")
        .containsExactly("2", "-7", "-4");
  }

  @Test
  void bsearchOfANonArrayCannotBeSearchedFrom() {
    assertThat(results("\"a\", {}, null, 1 | try bsearch(1) catch ."))
        .as("bsearch's errors")
        .containsExactly(
            "\"string (\\\"a\\\") cannot be searched from\"",
            "\"object ({}) cannot be searched from\"",
            "\"null (null) cannot be searched from\"",
            "\"number (1) cannot be searched from\"");
  }

  @Test
  void bsearchTicksOncePerProbe() {
    // A constant array costs its commas; 3 is found at the second probe of [1,2,3], 8 is past the third of [1..7].
    assertThat(completes("[1, 2, 3] | bsearch(3)", 4)).as("two probes with 4 steps").containsExactly("2");
    assertThat(ends("[1, 2, 3] | bsearch(3)", 3)).as("two probes with 3 steps").isEmpty();
    assertThat(completes("[1, 2, 3, 4, 5, 6, 7] | bsearch(8)", 9)).as("three probes with 9 steps").containsExactly("-8");
    assertThat(ends("[1, 2, 3, 4, 5, 6, 7] | bsearch(8)", 8)).as("three probes with 8 steps").isEmpty();
  }

  @Test
  void builtinsListsThePublicSignatures() {
    assertThat(results("builtins | length, .[0], .[-1], (map(select(startswith(\"_\"))) | length), (unique | length)"))
        .as("builtins: 226 distinct signatures, none hidden, jq-ref's first and last")
        .containsExactly("226", "\"have_literal_numbers/0\"", "\"builtins/0\"", "0", "226");
    assertThat(results("builtins | contains([\"contains/1\", \"inside/1\", \"bsearch/1\", \"builtins/0\"])")).as("this group's own signatures").containsExactly("true");
  }

  @Test
  void builtinsTicksOncePerElement() {
    assertThat(completes("builtins | length", 226)).as("builtins with 226 steps").containsExactly("226");
    assertThat(ends("builtins | length", 225)).as("builtins with 225 steps").isEmpty();
  }

  /** The result texts of {@code filter} on null, with no budget. */
  private static List<String> results(String filter) {
    Results all = new Results();
    ENGINE.compile(filter).run(JqNull.NULL, NONE, all, ENGINE.newWorkspace());
    return all.values.stream().map(JqValue::toJson).toList();
  }
}
