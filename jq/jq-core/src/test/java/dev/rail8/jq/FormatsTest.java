package dev.rail8.jq;

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
 * jq's formats ({@code @text}, {@code @json}, {@code @html}, {@code @uri}, {@code @urid}, {@code @csv}, {@code @tsv},
 * {@code @sh}, {@code @base64}, {@code @base64d}), {@code format/1} over them, and a format applied to each
 * interpolation of a string. Every run is on null, so no read ticks; the inputs are constants, and a string from {@code
 * "x" * n} folds at parse time, so a run ticks and charges only the format. Each pin is an adjacent pair: the run
 * completes under N and ends under N - 1. A string format ticks once per started KiB of the larger of its input and its
 * output; a row format counts its input as one byte per element. It charges its result, 40 B plus its length, before it
 * allocates. The expected results are jq-ref 1.8.2's, observed 2026-11-18.
 */
class FormatsTest {

  @Test
  void jsonAndTextDumpTheValue() {
    assertThat(results("[1,\"a\",null,true,{\"b\":[2]}] | @json")).containsExactly("\"[1,\\\"a\\\",null,true,{\\\"b\\\":[2]}]\"");
    assertThat(results("1 | @text, @json")).containsExactly("\"1\"", "\"1\"");
    assertThat(results("\"a\\u0000b\" | @json")).containsExactly("\"\\\"a\\\\u0000b\\\"\"");
  }

  @Test
  void htmlEscapesFiveCharactersAndNul() {
    assertThat(results("\"<&'\\\">x\" | @html")).containsExactly("\"&lt;&amp;&apos;&quot;&gt;x\"");
    // A value that is no string is dumped first.
    assertThat(results("[1,\"<\"] | @html")).containsExactly("\"[1,&quot;&lt;&quot;]\"");
    assertThat(results("\"a\\u0000b\" | @html")).containsExactly("\"a\\\\0b\"");
  }

  @Test
  void uriKeepsOnlyUnreservedBytes() {
    assertThat(results("\"a b/\\u00fc~-_.!\" | @uri")).containsExactly("\"a%20b%2F%C3%BC~-_.%21\"");
    assertThat(results("1 | @uri")).containsExactly("\"1\"");
  }

  @Test
  void uridDecodesPercentEscapesIntoValidUtf8() {
    assertThat(results("\"%41%42\" | @urid")).containsExactly("\"AB\"");
    assertThat(results("\"x\" | @urid")).containsExactly("\"x\"");
    assertThat(results("\"a%20b%C3%BC\" | @urid")).containsExactly("\"a b\u00fc\"");
    assertThat(results("\"a%20b%C3%BC%2\" | try @urid catch .")).containsExactly("\"string (\\\"a%20b%C3%BC%2\\\") is not a valid uri encoding\"");
    assertThat(results("\"%\" | try @urid catch .")).containsExactly("\"string (\\\"%\\\") is not a valid uri encoding\"");
    assertThat(results("\"%zz\" | try @urid catch .")).containsExactly("\"string (\\\"%zz\\\") is not a valid uri encoding\"");
    // A decoded byte that is no valid UTF-8 fails the whole string.
    assertThat(results("\"%FF\" | try @urid catch .")).containsExactly("\"string (\\\"%FF\\\") is not a valid uri encoding\"");
  }

  @Test
  void csvQuotesStringsAndLeavesNullEmpty() {
    assertThat(results("[1,\"a\\\"b\",null,true,false] | @csv")).containsExactly("\"1,\\\"a\\\"\\\"b\\\",,true,false\"");
    assertThat(results("[\"a\\u0000b\"] | @csv")).containsExactly("\"\\\"a\\\\0b\\\"\"");
    assertThat(results("[[1]] | try @csv catch .")).containsExactly("\"array ([1]) is not valid in a csv row\"");
    assertThat(results("\"x\" | try @csv catch .")).containsExactly("\"string (\\\"x\\\") cannot be csv-formatted, only array\"");
  }

  @Test
  void tsvEscapesTabsNewlinesAndBackslashes() {
    assertThat(results("[1,\"a\\tb\\\\c\\nd\\re\",null,true] | @tsv")).containsExactly("\"1\\ta\\\\tb\\\\\\\\c\\\\nd\\\\re\\t\\ttrue\"");
    assertThat(results("[\"a\\u0000b\"] | @tsv")).containsExactly("\"a\\\\0b\"");
    // jq names csv in the message of a bad tsv element too.
    assertThat(results("[{}] | try @tsv catch .")).containsExactly("\"object ({}) is not valid in a csv row\"");
    assertThat(results("{} | try @tsv catch .")).containsExactly("\"object ({}) cannot be tsv-formatted, only array\"");
  }

  @Test
  void shQuotesStringsAndWrapsAScalarInARow() {
    assertThat(results("\"it's\" | @sh")).containsExactly("\"'it'\\\\''s'\"");
    assertThat(results("[1,\"a b\",null,false] | @sh")).containsExactly("\"1 'a b' null false\"");
    assertThat(results("\"a\\u0000b\" | @sh")).containsExactly("\"'a\\\\0b'\"");
    assertThat(results("[[1]] | try @sh catch .")).containsExactly("\"array ([1]) can not be escaped for shell\"");
    assertThat(results("{\"a\":1} | try @sh catch .")).containsExactly("\"object ({\\\"a\\\":1}) can not be escaped for shell\"");
  }

  @Test
  void base64EncodesTheTextOfTheValue() {
    assertThat(results("\"hello\" | @base64")).containsExactly("\"aGVsbG8=\"");
    assertThat(results("[1] | @base64")).containsExactly("\"WzFd\"");
    assertThat(results("1 | @base64")).containsExactly("\"MQ==\"");
  }

  @Test
  void base64dStopsAtPaddingAndReplacesBadUtf8() {
    assertThat(results("\"aGVsbG8=\" | @base64d")).containsExactly("\"hello\"");
    assertThat(results("\"aGVsbG8=xyz\" | @base64d")).containsExactly("\"hello\"");
    assertThat(results("\"/w==\" | @base64d | explode")).containsExactly("[65533]");
    assertThat(results("\"a\" | try @base64d catch .")).containsExactly("\"string (\\\"a\\\") trailing base64 byte found\"");
    assertThat(results("1 | try @base64d catch .")).containsExactly("\"string (\\\"1\\\") trailing base64 byte found\"");
    assertThat(results("\"!!!!\" | try @base64d catch .")).containsExactly("\"string (\\\"!!!!\\\") is not valid base64 data\"");
  }

  @Test
  void formatTakesTheNameAsAString() {
    assertThat(results("\"x\" | format(\"text\"), format(\"json\")")).containsExactly("\"x\"", "\"\\\"x\\\"\"");
    assertThat(results("\"x\" | try format(\"nope\") catch .")).containsExactly("\"nope is not a valid format\"");
    assertThat(results("\"x\" | try format(1) catch .")).containsExactly("\"number (1) is not a valid format\"");
    // jq compares the name up to its first NUL.
    assertThat(results("\"x\" | format(\"json\\u0000y\")")).containsExactly("\"\\\"x\\\"\"");
    // An unknown format is an error of the run, not of the compile.
    assertThat(results("\"x\" | try @foo catch .")).containsExactly("\"foo is not a valid format\"");
  }

  @Test
  void aFormatAppliesToEachInterpolation() {
    assertThat(results("@base64 \"x\\(.)y\\(\"a\")\"")).containsExactly("\"xbnVsbA==yYQ==\"");
    assertThat(results("@json \"v=\\(\"a\")\"")).containsExactly("\"v=\\\"a\\\"\"");
    assertThat(results("@sh \"echo \\(\"it's\")\"")).containsExactly("\"echo 'it'\\\\''s'\"");
    assertThat(results("@uri \"?q=\\(\"a b\")\"")).containsExactly("\"?q=a%20b\"");
  }

  @Test
  void aStringFormatTicksOncePerStartedKibOfItsOutput() {
    // 768 bytes encode to 1024, 769 to 1028.
    assertSteps("\"a\" * 768 | @base64", 1);
    assertSteps("\"a\" * 769 | @base64", 2);
    // Each "<" becomes "&lt;".
    assertSteps("\"<\" * 256 | @html", 1);
    assertSteps("\"<\" * 257 | @html", 2);
    // The quotes make 1022 bytes 1024.
    assertSteps("\"a\" * 1022 | @json", 1);
    assertSteps("\"a\" * 1023 | @json", 2);
    assertSteps("\"a\" * 1024 | @uri", 1);
    assertSteps("\"a\" * 1025 | @uri", 2);
  }

  @Test
  void aDecodingFormatTicksOncePerStartedKibOfItsInput() {
    // 341 escapes are 1023 bytes, 342 are 1026.
    assertSteps("\"%41\" * 341 | @urid", 1);
    assertSteps("\"%41\" * 342 | @urid", 2);
    assertSteps("\"QUJD\" * 256 | @base64d", 1);
    assertSteps("\"QUJD\" * 257 | @base64d", 2);
  }

  @Test
  void aRowFormatTicksOncePerStartedKibOfItsOutput() {
    // The quotes make 1022 bytes 1024 for csv and sh; tsv adds none.
    assertSteps("[\"a\" * 1022] | @csv", 1);
    assertSteps("[\"a\" * 1023] | @csv", 2);
    assertSteps("[\"a\" * 1022] | @sh", 1);
    assertSteps("[\"a\" * 1023] | @sh", 2);
    assertSteps("[\"a\" * 1024] | @tsv", 1);
    assertSteps("[\"a\" * 1025] | @tsv", 2);
  }

  @Test
  void aRowFormatCountsEachElementOfItsInput() {
    // range ticks once per value. 1024 nulls give 1023 commas, one KiB of elements; 1025 nulls are two.
    assertSteps("[range(1024) | null] | @csv", 1025);
    assertSteps("[range(1025) | null] | @csv", 1027);
    assertSteps("[range(1024) | null] | @tsv", 1025);
    assertSteps("[range(1025) | null] | @tsv", 1027);
  }

  @Test
  void aFormatChargesItsResult() {
    // 40 B and 1024 bytes of base64.
    assertBytes("\"a\" * 768 | @base64", 1064);
    // 40 B and the 341 decoded bytes; 40 B and the 768 decoded bytes.
    assertBytes("\"%41\" * 341 | @urid", 381);
    assertBytes("\"QUJD\" * 256 | @base64d", 808);
    // The array, 64 B and 8 B for its element, then 40 B and the 1024 bytes of the row.
    assertBytes("[\"a\" * 1022] | @csv", 1136);
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
