package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #73's natives on built arrays and on a document's (jq 1.8.2's sort, unique, min, max and the {@code _…_impl} forms
 * under builtin.jq's {@code sort_by} and its kin). Every expected text is jq-ref 1.8.2's, recorded in diff/sort.rec for
 * the same filter and input, or observed 2026-11-13 with {@code jq-ref -nc} for the built forms of a recorded document;
 * the comparison cycle's is jqj's column of DEVIATIONS.md "Order inside a comparison cycle".
 */
class SortTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** The results of {@code filter} on {@code null}, as compact text. */
  private static List<String> run(String filter) {
    List<String> out = new ArrayList<>();
    ENGINE.compile(filter).run(JqNull.NULL, NONE, v -> out.add(v.toJson()), ENGINE.newWorkspace());
    return out;
  }

  /** The results of {@code filter} on the document {@code json}, as compact text, each taken while the document is read. */
  private static List<String> run(String filter, String json) {
    Workspace workspace = ENGINE.newWorkspace();
    JqValue root = workspace.read(MemorySegment.ofArray(json.getBytes(StandardCharsets.UTF_8))).root();
    List<String> out = new ArrayList<>();
    ENGINE.compile(filter).run(root, NONE, v -> out.add(v.toJson()), workspace);
    return out;
  }

  @Test
  void sortOrdersEveryKind() {
    assertThat(run("[{\"b\":1},{\"a\":2},{\"a\":1,\"b\":0},{},[2],[1,5],[1],[],\"z\",\"ab\",\"a\",\"\",2,1.5,-1,0,true,false,null] | sort"))
        .as("sort/kinds-mixed-built without its non-finite numbers")
        .containsExactly("[null,false,true,-1,0,1.5,2,\"\",\"a\",\"ab\",\"z\",[],[1],[1,5],[2],{},{\"a\":2},{\"a\":1,\"b\":0},{\"b\":1}]");
    assertThat(run("sort", "[{\"b\":1},[2],\"z\",3,null,[1],\"a\",1]")).as("sort of a document's array").containsExactly("[null,1,3,\"a\",\"z\",[1],[2],{\"b\":1}]");
  }

  @Test
  void equalValuesKeepTheirOrderMinTheFirstAndMaxTheLast() {
    assertThat(run("[min, max, unique, sort]", "[1.0,1,1.00,0.5e1,5,5.0]"))
        .as("sort/min-max-equal-values")
        .containsExactly("[1.0,5.0,[1.0,5],[1.0,1,1.00,5,5,5.0]]");
  }

  @Test
  void theByFormsAreStableOnEqualKeys() {
    String input = "[{\"k\":2,\"i\":0},{\"k\":1,\"i\":1},{\"k\":2,\"i\":2},{\"k\":1,\"i\":3},{\"k\":1.0,\"i\":6}]";
    String filter = "(sort_by(.k) | map(.i)), (group_by(.k) | map(map(.i))), (unique_by(.k) | map(.i)), min_by(.k).i, max_by(.k).i";
    List<String> expected = List.of("[1,3,6,0,2]", "[[1,3,6],[0,2]]", "[1,0]", "1", "2");
    assertThat(run(filter, input)).as("the by forms on a document's array").isEqualTo(expected);
    assertThat(run(input + " | " + filter)).as("the by forms on a built array").isEqualTo(expected);
  }

  @Test
  void aLongSortIsStable() {
    assertThat(run("[range(40) | [nan, (. * 7) % 40]] | sort | map(.[1])"))
        .as("sort/nan-pairs-long")
        .containsExactly("[0,7,14,21,28,35,2,9,16,23,30,37,4,11,18,25,32,39,6,13,20,27,34,1,8,15,22,29,36,3,10,17,24,31,38,5,12,19,26,33]");
  }

  @Test
  void aPairOfNansKeepsItsOrder() {
    assertThat(run("([[nan, 2], [nan, 1]] | sort), ([[nan, 1], [nan, 2], [nan, 3]] | min_by(.[0]), max_by(.[0])), ([nan, 1, nan] | unique, min, max)"))
        .as("sort/nan-pairs-keep-their-order")
        .containsExactly("[[null,2],[null,1]]", "[null,3]", "[null,1]", "[null,null,1]", "null", "1");
  }

  @Test
  void groupByGroupsEqualObjectKeys() {
    assertThat(run("group_by(.)", "[{\"a\":1},{\"b\":1},{\"a\":1},{\"a\":1,\"b\":2},{\"b\":2,\"a\":1}]"))
        .as("sort/group-by-objects")
        .containsExactly("[[{\"a\":1},{\"a\":1}],[{\"a\":1,\"b\":2},{\"b\":2,\"a\":1}],[{\"b\":1}]]");
  }

  @Test
  void emptyArrays() {
    assertThat(run("[min, max, min_by(.a), max_by(.a), sort, unique, sort_by(.a), group_by(.a), unique_by(.a)]", "[]"))
        .as("sort/min-max-of-empty and sort/empty-sorts")
        .containsExactly("[null,null,null,null,[],[],[],[],[]]");
  }

  @Test
  void errorsNameTheKinds() {
    assertThat(run(".[] | [(try sort catch .), (try unique catch .), (try min catch .), (try max catch .)]", "[1,\"a\"]"))
        .as("sort/errors-not-an-array")
        .containsExactly(
            "[\"number (1) cannot be sorted, as it is not an array\",\"number (1) cannot be sorted, as it is not an array\",\"number (1) and number (1) cannot be iterated over\",\"number (1) and number (1) cannot be iterated over\"]",
            "[\"string (\\\"a\\\") cannot be sorted, as it is not an array\",\"string (\\\"a\\\") cannot be sorted, as it is not an array\",\"string (\\\"a\\\") and string (\\\"a\\\") cannot be iterated over\",\"string (\\\"a\\\") and string (\\\"a\\\") cannot be iterated over\"]");
    assertThat(
            run(
                "[1, 2] | (try _sort_by_impl([1]) catch .), (try _group_by_impl({}) catch .), (try _unique_by_impl(\"a\") catch .), (try _min_by_impl([1]) catch .), (try _max_by_impl(null) catch .), (try _min_by_impl({\"a\": 1}) catch .), (try _max_by_impl([1, 2, 3]) catch .)"))
        .as("sort/impl-errors")
        .containsExactly(
            "\"array ([1,2]) and array ([1]) cannot be sorted, as they are not both arrays\"",
            "\"array ([1,2]) and object ({}) cannot be sorted, as they are not both arrays\"",
            "\"array ([1,2]) and string (\\\"a\\\") cannot be sorted, as they are not both arrays\"",
            "\"array ([1,2]) and array ([1]) have wrong length\"",
            "\"array ([1,2]) and null (null) cannot be iterated over\"",
            "\"array ([1,2]) and object ({\\\"a\\\":1}) cannot be iterated over\"",
            "\"array ([1,2]) and array ([1,2,3]) have wrong length\"");
    assertThat(run("\"a\" | (try _sort_by_impl([]) catch .), (try _min_by_impl([]) catch .)"))
        .as("sort/impl-errors-input on a string")
        .containsExactly(
            "\"string (\\\"a\\\") and array ([]) cannot be sorted, as they are not both arrays\"", "\"string (\\\"a\\\") and array ([]) cannot be iterated over\"");
  }

  @Test
  void comparisonTooDeep() {
    assertThat(
            run(
                "(reduce range(10001) as $_ ([]; [.])) as $x | [$x, $x] | (try sort catch .), (try min catch .), (try max catch .), (try group_by(.[0]) catch .), (try unique_by(.) catch .)"))
        .as("sort/depth-10002-arrays-too-deep")
        .containsOnly("\"Comparison too deep\"")
        .hasSize(5);
    assertThat(run("(reduce range(10000) as $_ ([]; [.])) as $x | [([$x, $x] | sort | length), ([$x, $x] | min | length), ([$x, $x] | unique | length)]"))
        .as("sort/depth-10001-arrays-compare")
        .containsExactly("[2,1,1]");
  }

  @Test
  void equalityTooDeepAfterAnEarlyOrder() {
    assertThat(
            run(
                "(reduce range(10001) as $_ ([]; [.])) as $x | (reduce range(10001) as $_ ([]; [.])) as $y | (try ([{\"b\": $x, \"a\": 1}, {\"b\": $y, \"a\": 2}] | group_by(.) | length) catch .), (try ([{\"a\": 1, \"b\": $x}, {\"a\": 2, \"b\": $y}] | group_by(.) | length) catch .), (try ([{\"b\": $x, \"a\": 1}, {\"b\": $y, \"a\": 2}] | unique | length) catch .)"))
        .as("sort/equality-too-deep")
        .containsExactly("\"Equality check too deep\"", "2", "\"Equality check too deep\"");
  }

  @Test
  void aComparisonCycleTakesJqjsMergeOrder() {
    assertThat(run("[100000000000000000001,(1e20+0),100000000000000000000] | sort, unique, group_by(.), min, max"))
        .as("DEVIATIONS.md comparison cycle, jqj's column, and min and max as jq's")
        .containsExactly(
            "[100000000000000000000,100000000000000000001,1e+20]",
            "[100000000000000000000,100000000000000000001]",
            "[[100000000000000000000],[100000000000000000001,1e+20]]",
            "100000000000000000000",
            "100000000000000000000");
  }
}
