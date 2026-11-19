package dev.rail8.jq;

import static dev.rail8.jq.DeepRuns.assertJqError;
import static dev.rail8.jq.DeepRuns.assertResult;
import static dev.rail8.jq.DeepRuns.onSmallStack;
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
 * The path natives {@code getpath/1}, {@code setpath/2} and {@code delpaths/1} in value mode: jq 1.8.2's {@code
 * jv_getpath}, {@code jv_setpath} and {@code jv_delpaths} with {@code delpaths_sorted} and {@code jv_dels} (src/jv_aux.c),
 * their errors in jq's order, and "Path too deep" past 10 000 components. The expected values are jq-ref 1.8.2's
 * outputs, observed 2026-11-19, or jq's own test cases (jq.test's "Path too deep" cases among them). Every run is on
 * {@code null} unless the filter builds its input; a constant array of n elements costs n - 1 steps, a native call none
 * of its own.
 */
class PathNativesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void getpathReadsEachComponentAsDotBracketDoes() {
    assertThat(results("{\"a\":{\"b\":0,\"c\":1}} | [getpath([\"a\",\"b\"], [\"a\",\"c\"])]")).as("getpath of two paths").containsExactly("[0,1]");
    assertThat(results("getpath([\"a\",\"b\"])")).as("getpath on null").containsExactly("null");
    assertThat(results("{\"a\":null} | getpath([\"a\",\"b\",\"c\"])")).as("getpath past a null").containsExactly("null");
    assertThat(results("[[0],[0,1],[0,1,2]] | map(getpath([2]))")).as("getpath past an array's end").containsExactly("[null,null,2]");
    assertThat(results("[1,2,3] | getpath([-1]), getpath([1.7])")).as("getpath of a negative and a fractional index").containsExactly("3", "2");
    assertThat(results("\"abcd\" | getpath([{\"start\":1,\"end\":3}])")).as("getpath of a string slice").containsExactly("\"bc\"");
    assertThat(results("[[1,2,3]] | getpath([0, {\"start\":1,\"end\":null}])")).as("getpath of an array slice").containsExactly("[2,3]");
    assertThat(results("{\"a\":[1]} | getpath([])")).as("getpath of the empty path").containsExactly("{\"a\":[1]}");
  }

  @Test
  void getpathRaisesTheFirstErrorOfItsWalk() {
    assertThat(results("try getpath(\"a\") catch .")).as("getpath of no array").containsExactly("\"Path must be specified as an array\"");
    assertThat(results("{\"a\":1} | try getpath([\"a\",\"b\"]) catch .")).as("getpath into a number").containsExactly("\"Cannot index number with string (\\\"b\\\")\"");
    assertThat(results("{} | try getpath([0]) catch .")).as("getpath of a number on an object").containsExactly("\"Cannot index object with number (0)\"");
    assertThat(results("try getpath([true]) catch .")).as("getpath of a boolean on null").containsExactly("\"Cannot index null with boolean (true)\"");
    assertThat(results("[1] | try getpath([0, \"a\", true]) catch .")).as("getpath stops at its first error").containsExactly("\"Cannot index number with string (\\\"a\\\")\"");
    assertThat(results("{\"a\":[1]} | try getpath([\"a\", {\"start\":0}]) catch .")).as("getpath of a slice without an end").containsExactly("\"Array/string slice indices must be integers\"");
  }

  @Test
  void setpathBuildsEachLevelAsJvSetDoes() {
    assertThat(results("setpath([\"a\",\"b\"]; 1)")).as("setpath on null").containsExactly("{\"a\":{\"b\":1}}");
    assertThat(results("{\"a\":{\"b\":0}} | setpath([\"a\",\"b\"]; 1)")).as("setpath of a present key").containsExactly("{\"a\":{\"b\":1}}");
    assertThat(results("setpath([0,\"a\"]; 1)")).as("setpath of an index on null").containsExactly("[{\"a\":1}]");
    assertThat(results("[0] | setpath([-1]; 1)")).as("setpath of a negative index").containsExactly("[1]");
    assertThat(results("setpath([2]; 1), ([1,2] | setpath([4]; 5))")).as("setpath past the end pads with null").containsExactly("[null,null,1]", "[1,2,null,null,5]");
    assertThat(results("[1,2,3] | setpath([1.7]; 0)")).as("setpath of a fractional index").containsExactly("[1,0,3]");
    assertThat(results("{\"b\":1,\"a\":2} | setpath([\"c\"]; 3), setpath([\"b\"]; 3)"))
        .as("a new key goes last, a present one keeps its place")
        .containsExactly("{\"b\":1,\"a\":2,\"c\":3}", "{\"b\":3,\"a\":2}");
    assertThat(results("[] | setpath([]; 5)")).as("setpath of the empty path").containsExactly("5");
  }

  @Test
  void setpathAssignsSlicesAsJvSetDoes() {
    assertThat(results("[1,2,3,4] | setpath([{\"start\":1,\"end\":3}]; [\"x\"])")).as("a slice shrinks").containsExactly("[1,\"x\",4]");
    assertThat(results("[1,2] | setpath([{\"start\":1,\"end\":1}]; [7,8,9])")).as("a slice grows").containsExactly("[1,7,8,9,2]");
    assertThat(results("setpath([{\"start\":1,\"end\":2}]; [5])")).as("a slice of null").containsExactly("[5]");
    assertThat(results("[1,2,3] | setpath([{\"start\":-1,\"end\":null}]; [])")).as("a slice from the end").containsExactly("[1,2]");
    assertThat(results("[[1,2,3]] | setpath([0, {\"start\":0,\"end\":2}, 0]; 9)")).as("a path through a slice").containsExactly("[[9,2,3]]");
  }

  @Test
  void setpathRaisesItsErrorsInJqsOrder() {
    assertThat(results("try setpath(\"a\"; 1) catch .")).as("setpath of no array").containsExactly("\"Path must be specified as an array\"");
    assertThat(results("{\"hi\":\"hello\"} | try setpath([1]; 1) catch .")).as("setpath of a number on an object").containsExactly("\"Cannot index object with number (1)\"");
    assertThat(results("[] | try setpath([[1]]; 1) catch .")).as("setpath of an array key").containsExactly("\"Cannot update field at array index of array\"");
    assertThat(results("try setpath([-1]; 1) catch ., ([1] | try setpath([-2]; 1) catch .)"))
        .as("setpath before an array's start")
        .containsExactly("\"Out of bounds negative array index\"", "\"Out of bounds negative array index\"");
    assertThat(results("[] | try setpath([nan]; 1) catch .")).as("setpath of a NaN index").containsExactly("\"Cannot set array element at NaN index\"");
    assertThat(results("try setpath([536870912]; 1) catch .")).as("setpath of a huge index").containsExactly("\"Array index too large\"");
    assertThat(results("\"abc\" | try setpath([{\"start\":0,\"end\":1}]; \"x\") catch .")).as("setpath of a string slice").containsExactly("\"Cannot update string slices\"");
    assertThat(results("[1,2] | try setpath([{\"start\":0,\"end\":1}]; 5) catch .")).as("setpath of a slice to no array").containsExactly("\"A slice of an array can only be assigned another array\"");
    assertThat(results("try setpath([{\"start\":\"x\"}]; [1]) catch .")).as("setpath of a slice with bad bounds on null").containsExactly("\"Array/string slice indices must be integers\"");
    assertThat(results("{\"a\":1} | try setpath([\"a\",\"b\"]; 1) catch .")).as("setpath into a number").containsExactly("\"Cannot index number with string (\\\"b\\\")\"");
    assertThat(results("try setpath([-1, \"a\", true]; 1) catch .")).as("a level's error comes before a deeper one").containsExactly("\"Out of bounds negative array index\"");
    assertThat(results("\"abc\" | try setpath([{\"start\":0,\"end\":1}, \"x\"]; 1) catch .")).as("a deeper error comes before a slice's own").containsExactly("\"Cannot index string with string (\\\"x\\\")\"");
  }

  @Test
  void setpathRunsItsValuesOutermost() {
    assertThat(results("[setpath([\"a\"], [\"b\"]; 1, 2)]")).as("setpath's arguments, the last outermost").containsExactly("[{\"a\":1},{\"b\":1},{\"a\":2},{\"b\":2}]");
  }

  @Test
  void delpathsDeletesSortedPathsAsJvDelpathsDoes() {
    assertThat(results("{\"a\":{\"b\":1},\"x\":{\"y\":2}} | delpaths([[\"a\",\"b\"]])")).as("delpaths of a nested key").containsExactly("{\"a\":{},\"x\":{\"y\":2}}");
    assertThat(results("[1,2,3] | delpaths([[-200]])")).as("delpaths before the start").containsExactly("[1,2,3]");
    assertThat(results("[[{\"foo\":2,\"x\":1}],[{\"bar\":2}]] | map(delpaths([[0,\"foo\"]]))")).as("delpaths in each element").containsExactly("[[{\"x\":1}],[{\"bar\":2}]]");
    assertThat(results("[1,2,3,4] | delpaths([[0],[-1]]), delpaths([[1],[1]]), delpaths([[-1],[2]]), delpaths([[-1],[3]])"))
        .as("delpaths of indexes from either end, repeated or naming one element twice")
        .containsExactly("[2,3]", "[1,3,4]", "[1,2]", "[1,2,3]");
    assertThat(results("[0,1,2,3] | delpaths([[1.5]]), delpaths([[-0.5]]), delpaths([[nan]])"))
        .as("delpaths of a fraction, a negative fraction and NaN")
        .containsExactly("[0,2,3]", "[0,1,2,3]", "[0,1,2,3]");
    assertThat(results("[1,2,3,4,5] | delpaths([[{\"start\":1,\"end\":3}]])")).as("delpaths of a slice").containsExactly("[1,4,5]");
    assertThat(results("{\"a\":1,\"b\":2,\"c\":3} | delpaths([[\"c\"],[\"a\"]])")).as("delpaths of two keys").containsExactly("{\"b\":2}");
    assertThat(results("[1,[2,3]] | delpaths([[1,0],[0]])")).as("delpaths in and of an element").containsExactly("[[3]]");
    assertThat(results("[[1,2],[3,4]] | delpaths([[-1,0],[1,1]])"))
        .as("two deletes in one element, the second reading what the first wrote")
        .containsExactly("[[1,2],[4]]");
    assertThat(results("[1,2,3] | delpaths([[1],[1,0]])")).as("a deleted key drops its longer paths").containsExactly("[1,3]");
    assertThat(results("5 | delpaths([])")).as("delpaths of no path").containsExactly("5");
    assertThat(results("{\"a\":1} | delpaths([[]]), delpaths([[\"a\"],[]])")).as("delpaths of the empty path").containsExactly("null", "null");
    assertThat(results("delpaths([[\"a\",\"b\"]]), ({\"a\":null} | delpaths([[\"a\",\"b\"]]))")).as("delpaths through null").containsExactly("null", "{\"a\":null}");
  }

  @Test
  void delpathsSetsThenDeletesEachLevelInSortedOrder() {
    // [3] sorts before the slice: index 3 enters the delete list, the slice's update shortens the array, and the delete
    // finds no index 3 any more, as jq 1.8.2's delpaths_sorted runs it.
    assertThat(results("[1,2,3,4] | delpaths([[{\"start\":0,\"end\":2}, 0], [3]])")).as("a slice's update before the deletes").containsExactly("[2,3,4]");
  }

  @Test
  void delpathsChecksItsPathsInSortedOrder() {
    assertThat(results("{} | try delpaths(0) catch .")).as("delpaths of no array").containsExactly("\"Paths must be specified as an array\"");
    assertThat(results("try delpaths([0]) catch .")).as("delpaths of a number").containsExactly("\"Path must be specified as array, not number\"");
    assertThat(results("try delpaths([[range(10001)|0], 0]) catch .")).as("a number sorts before a deep path").containsExactly("\"Path must be specified as array, not number\"");
    assertThat(results("try delpaths([[range(10001)|0], {}]) catch .")).as("a deep path sorts before an object").containsExactly("\"Path too deep\"");
  }

  @Test
  void delpathsRaisesItsErrorsInJqsOrder() {
    assertThat(results("{\"a\":1,\"b\":[1]} | try delpaths([[\"b\",\"x\"],[\"a\",0]]) catch .")).as("the first sorted path's error").containsExactly("\"Cannot delete fields from number\"");
    assertThat(results("[1,2] | try delpaths([[\"a\"]]) catch .")).as("a string key on an array").containsExactly("\"Cannot delete string element of array\"");
    assertThat(results("{\"a\":1} | try delpaths([[0]]) catch .")).as("a number key on an object").containsExactly("\"Cannot delete number field of object\"");
    assertThat(results("5 | try delpaths([[\"a\"]]) catch .")).as("a key on a number").containsExactly("\"Cannot delete fields from number\"");
    assertThat(results("[1] | try delpaths([[{\"start\":\"x\"}]]) catch .")).as("a slice with bad bounds").containsExactly("\"Array/string slice indices must be integers\"");
    assertThat(results("{\"a\":5} | try delpaths([[\"a\",\"b\",\"c\"]]) catch .")).as("a walk into a number").containsExactly("\"Cannot index number with string (\\\"b\\\")\"");
    assertThat(results("[[1]] | try delpaths([[[0], 0]]) catch .")).as("an update at an array key").containsExactly("\"Cannot update field at array index of array\"");
  }

  @Test
  void eachNativeTakesAPathOf10000ComponentsAndRaisesAt10001() {
    assertThat(results("getpath([range(10000) | 0])")).as("getpath of 10 000").containsExactly("null");
    assertThat(results("try getpath([range(10001) | 0]) catch .")).as("getpath of 10 001").containsExactly("\"Path too deep\"");
    assertThat(results("setpath([range(10000) | 0]; 0) | getpath([range(9999) | 0])")).as("setpath of 10 000").containsExactly("[0]");
    assertThat(results("try setpath([range(10001) | 0]; 0) catch .")).as("setpath of 10 001").containsExactly("\"Path too deep\"");
    assertThat(results("delpaths([[range(10000) | 0]])")).as("delpaths of 10 000").containsExactly("null");
    assertThat(results("try delpaths([[range(10001) | 0]]) catch .")).as("delpaths of 10 001").containsExactly("\"Path too deep\"");
  }

  @Test
  void deepPathsRunOnAnExplicitStack() {
    String deep = "setpath([range(10000) | 0]; 0)";
    assertResult(onSmallStack(deep + " | getpath([range(10000) | 0])", JqNull.NULL), "0", "setpath then getpath of 10 000 levels");
    assertResult(onSmallStack(deep + " | delpaths([[range(10000) | 0]]) | getpath([range(9999) | 0])", JqNull.NULL), "[]", "delpaths at 10 000 levels");
    assertJqError(onSmallStack("setpath([range(10001) | 0]; 0)", JqNull.NULL), "Path too deep", "setpath of 10 001 levels");
  }

  @Test
  void getpathAndSetpathTickPerKeyAndSetpathPerSlotItBuilds() {
    // The constant path ["a","b"] costs its comma.
    assertThat(completes("getpath([\"a\",\"b\"])", 3)).as("getpath of two keys with 3 steps").containsExactly("null");
    assertThat(ends("getpath([\"a\",\"b\"])", 2)).as("getpath of two keys with 2 steps").isEmpty();
    // Two keys, then two new objects of one entry each.
    assertThat(completes("setpath([\"a\",\"b\"]; 1)", 5)).as("setpath of two keys with 5 steps").containsExactly("{\"a\":{\"b\":1}}");
    assertThat(ends("setpath([\"a\",\"b\"]; 1)", 4)).as("setpath of two keys with 4 steps").isEmpty();
    // The input's two commas, one key, a copy of three slots.
    assertThat(completes("[1,2,3] | setpath([0]; 9)", 6)).as("setpath into three elements with 6 steps").containsExactly("[9,2,3]");
    assertThat(ends("[1,2,3] | setpath([0]; 9)", 5)).as("setpath into three elements with 5 steps").isEmpty();
  }

  @Test
  void delpathsTicksPerPathKeyAndElementItCopiesOrDrops() {
    // The input's two commas, one path, one key, three elements compacted.
    assertThat(completes("[1,2,3] | delpaths([[0]])", 7)).as("delpaths of one element with 7 steps").containsExactly("[2,3]");
    assertThat(ends("[1,2,3] | delpaths([[0]])", 6)).as("delpaths of one element with 6 steps").isEmpty();
    // The input's comma and the path's, one path, two keys, two elements compacted below, one copied to write above.
    assertThat(completes("[[1,2]] | delpaths([[0,0]])", 8)).as("delpaths one level down with 8 steps").containsExactly("[[2]]");
    assertThat(ends("[[1,2]] | delpaths([[0,0]])", 7)).as("delpaths one level down with 7 steps").isEmpty();
    // The input's comma and the paths' three, two paths, one comparison, four keys, one element dropped below each, and
    // two slots copied once above for both writes.
    assertThat(completes("[[1],[2]] | delpaths([[0,0],[1,0]])", 15)).as("delpaths of two writes into one level with 15 steps").containsExactly("[[],[]]");
    assertThat(ends("[[1],[2]] | delpaths([[0,0],[1,0]])", 14)).as("delpaths of two writes into one level with 14 steps").isEmpty();
  }

  /** The result texts of {@code filter} on null. */
  private static List<String> results(String filter) {
    Results all = new Results();
    assertThat(ENGINE.compile(filter).run(JqNull.NULL, NONE, all, ENGINE.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }
}
