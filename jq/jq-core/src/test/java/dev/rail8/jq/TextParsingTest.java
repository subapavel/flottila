package dev.rail8.jq;

import static dev.rail8.jq.DeepRuns.assertJqError;
import static dev.rail8.jq.DeepRuns.assertResult;
import static dev.rail8.jq.DeepRuns.onSmallStack;
import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.bytesBuilt;
import static dev.rail8.jq.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code fromjson} and {@code toboolean}, the two builtins that read a value from a string. {@code fromjson} reads the
 * string as one strict JSON document with the JSON reader: it keeps literal numbers, skips a BOM at the start, and
 * raises jq's "Expected JSON value" for no value and "Unexpected extra JSON values" for more than one, each followed by
 * the text it read. It ticks once per started KiB of the text and charges what the same value built would charge.
 * {@code toboolean} accepts a boolean or exactly "true" or "false", and ticks nothing. Every run is on null, and a
 * string from {@code "x" * n} or a sum of constants folds at parse time, so a run ticks and charges only the builtin.
 * The expected results are jq-ref 1.8.2's, observed 2026-11-18.
 */
class TextParsingTest {

  @Test
  void fromjsonReadsOneDocument() {
    assertThat(results("\"[1,\\\"ab\\\",{\\\"k\\\":[]}]\" | fromjson")).containsExactly("[1,\"ab\",{\"k\":[]}]");
    assertThat(results("\"  {\\\"a\\\":1} \" | fromjson")).containsExactly("{\"a\":1}");
    assertThat(results("\"\\\"\\\\u00e9\\\"\" | fromjson")).containsExactly("\"\u00e9\"");
    assertThat(results("\"\\ufeff[1]\" | fromjson")).containsExactly("[1]");
    // The last of two equal keys wins.
    assertThat(results("\"{\\\"a\\\":\\\"xyz\\\",\\\"a\\\":1}\" | fromjson")).containsExactly("{\"a\":1}");
    assertThat(results("[1,{\"a\":2}] | tojson | fromjson")).containsExactly("[1,{\"a\":2}]");
  }

  @Test
  void fromjsonKeepsLiteralNumbers() {
    assertThat(results("\"1E+1000\" | fromjson | [., tojson, . == 1E1000]")).containsExactly("[1E+1000,\"1E+1000\",true]");
    assertThat(results("\"100000000000000000001\" | fromjson | [., tojson, . == 100000000000000000000]"))
        .containsExactly("[100000000000000000001,\"100000000000000000001\",false]");
    assertThat(results("\"[1.10, 1.000]\" | fromjson")).containsExactly("[1.10,1.000]");
  }

  @Test
  void fromjsonNamesTheTextInItsErrors() {
    assertThat(results("\"\" | try fromjson catch .")).containsExactly("\"Expected JSON value (while parsing '')\"");
    assertThat(results("\"  \" | try fromjson catch .")).containsExactly("\"Expected JSON value (while parsing '  ')\"");
    assertThat(results("\"1 2\" | try fromjson catch .")).containsExactly("\"Unexpected extra JSON values (while parsing '1 2')\"");
    assertThat(results("\"[1,2] 3\" | try fromjson catch .")).containsExactly("\"Unexpected extra JSON values (while parsing '[1,2] 3')\"");
    assertThat(results("1 | try fromjson catch .")).containsExactly("\"number (1) only strings can be parsed\"");
    assertThat(results("null | try fromjson catch .")).containsExactly("\"null (null) only strings can be parsed\"");
  }

  @Test
  void fromjsonTicksOncePerStartedKibOfTheText() {
    // The quotes make 1022 bytes 1024.
    assertSteps("\"\\\"\" + \"a\" * 1022 + \"\\\"\" | fromjson", 1);
    assertSteps("\"\\\"\" + \"a\" * 1023 + \"\\\"\" | fromjson", 2);
  }

  @Test
  void fromjsonChargesWhatTheBuiltValueCharges() {
    // The array 64 + 3 x 8, the string 40 + 2, the object 64 + 16 + 40 + 1 for its key, its array 64.
    assertBytes("\"[1,\\\"ab\\\",{\\\"k\\\":[]}]\" | fromjson", 315);
    // Only the kept entry: the object 64 + 16 + 40 + 1.
    assertBytes("\"{\\\"a\\\":\\\"xyz\\\",\\\"a\\\":1}\" | fromjson", 121);
  }

  @Test
  void fromjsonReads10000LevelsOnASmallStack() {
    String deep = "\"[\" * 10000 + \"]\" * 10000 | fromjson | tojson | length";
    assertResult(onSmallStack(deep, JqNull.NULL), "20000", "fromjson of 10 000 nested arrays");
    String text = "[".repeat(10_001) + "]".repeat(10_001);
    assertJqError(
        onSmallStack("\"[\" * 10001 + \"]\" * 10001 | fromjson", JqNull.NULL),
        "Exceeds depth limit for parsing at line 1, column 10001 (while parsing '" + text + "')",
        "fromjson of 10 001 nested arrays");
  }

  @Test
  void tobooleanAcceptsBooleansAndTheirExactText() {
    assertThat(results("\"true\", \"false\", true, false | toboolean")).containsExactly("true", "false", "true", "false");
    assertThat(results("\"True\" | try toboolean catch .")).containsExactly("\"string (\\\"True\\\") cannot be parsed as a boolean\"");
    assertThat(results("\" true\" | try toboolean catch .")).containsExactly("\"string (\\\" true\\\") cannot be parsed as a boolean\"");
    assertThat(results("1 | try toboolean catch .")).containsExactly("\"number (1) cannot be parsed as a boolean\"");
    assertThat(results("null | try toboolean catch .")).containsExactly("\"null (null) cannot be parsed as a boolean\"");
    assertThat(results("[] | try toboolean catch .")).containsExactly("\"array ([]) cannot be parsed as a boolean\"");
  }

  @Test
  void tobooleanTicksNothing() {
    assertThat(completes(steps(0), "\"true\" | toboolean")).containsExactly("true");
    assertThat(completes(steps(0), "false | toboolean")).containsExactly("false");
  }

  /** The compact text of each result of {@code filter} on null, without a budget. */
  private static List<String> results(String filter) {
    return completes(RunBudgets.unlimited(), filter);
  }

  private static List<String> completes(JqEngine engine, String filter) {
    Results all = new Results();
    assertThat(engine.compile(filter).run(JqNull.NULL, NONE, all, engine.newWorkspace())).as("run's answer for %s", filter).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  /** {@code filter} on null completes with {@code limit} steps and ends with one fewer. */
  private static void assertSteps(String filter, long limit) {
    assertThat(completes(steps(limit), filter)).as("results of %s with %d steps", filter, limit).hasSize(1);
    JqEngine under = steps(limit - 1);
    assertBudget(() -> under.compile(filter).run(JqNull.NULL, NONE, new Results(), under.newWorkspace()), Budget.STEPS, filter + " with " + (limit - 1) + " steps");
  }

  /** {@code filter} on null completes under {@code limit} bytes built and ends under one fewer. */
  private static void assertBytes(String filter, long limit) {
    assertThat(completes(bytesBuilt(limit), filter)).as("results of %s under %d bytes built", filter, limit).hasSize(1);
    JqEngine under = bytesBuilt(limit - 1);
    assertBudget(() -> under.compile(filter).run(JqNull.NULL, NONE, new Results(), under.newWorkspace()), Budget.BYTES_BUILT, filter + " under " + (limit - 1) + " bytes built");
  }
}
