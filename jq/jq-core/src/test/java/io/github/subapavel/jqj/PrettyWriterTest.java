package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import java.nio.channels.Channels;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The pretty writer at the API seam (ADR-0015, ADR-0024, ADR-0027, #37): {@code JsonWriter.sink(..., pretty())} gives
 * the bytes the command line gives without {@code -c}, jq's default indent 2. Every expected text is the stdout that
 * scripts/oracle-record.sh recorded from jq-ref 1.8.2 in oracle/src/test/resources/diff/pretty.rec, under the case id
 * named beside it, for {@code jq-ref .} on the same document.
 */
class PrettyWriterTest {

  /** The pretty output of one read document's root, checked to be the same through both overloads. */
  private static String pretty(String json) {
    Workspace workspace = newWorkspace();
    RecordingTarget stream = new RecordingTarget();
    JsonWriter.sink(workspace, stream, OutputOptions.pretty()).emit(read(workspace, json).root());
    RecordingTarget behindChannel = new RecordingTarget();
    JsonWriter.sink(workspace, Channels.newChannel(behindChannel), OutputOptions.pretty()).emit(read(workspace, json).root());
    assertThat(behindChannel.text()).as("the channel overload's output of %s", json).isEqualTo(stream.text());
    return stream.text();
  }

  static Stream<Arguments> recorded() {
    return Stream.of(
        Arguments.argumentSet("pretty/null", "null", "null\n"),
        Arguments.argumentSet("pretty/true", "true\n", "true\n"),
        Arguments.argumentSet("pretty/false", " false ", "false\n"),
        Arguments.argumentSet("pretty/number", "-1.5\n", "-1.5\n"),
        Arguments.argumentSet("pretty/string", "\"a b/c é€𝄞 x\"\n", "\"a b/c é€𝄞 x\"\n"),
        Arguments.argumentSet(
            "pretty/array",
            "[ 1 ,\t\"two\" ,\n[ null ] , [true,false], -0, 100000000000000000000 ]\n",
            "[\n  1,\n  \"two\",\n  [\n    null\n  ],\n  [\n    true,\n    false\n  ],\n  -0,\n  100000000000000000000\n]\n"),
        Arguments.argumentSet(
            "pretty/object",
            "{\n  \"a\" : 1,\n\t\"b\" : [true, false],\n  \"c\" : {\"d\" : null, \"e\" : \"f\"}\n}\n",
            "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    false\n  ],\n  \"c\": {\n    \"d\": null,\n    \"e\": \"f\"\n  }\n}\n"),
        Arguments.argumentSet("pretty/empty-array", "[ ]\n", "[]\n"),
        Arguments.argumentSet("pretty/empty-object", "{\n}\n", "{}\n"),
        Arguments.argumentSet(
            "pretty/nested-empty", "{\"a\":[],\"b\":{},\"c\":[[]]}\n", "{\n  \"a\": [],\n  \"b\": {},\n  \"c\": [\n    []\n  ]\n}\n"),
        Arguments.argumentSet(
            "pretty/nested-empty-mixed",
            "[[ ],{\n},[[\t]],{\"a\":{ }},[{}],{\"b\":[[],{}]},[[[[]]]],{\"\":[]}]",
            "[\n  [],\n  {},\n  [\n    []\n  ],\n  {\n    \"a\": {}\n  },\n  [\n    {}\n  ],\n  {\n    \"b\": [\n      [],\n      {}\n    ]\n  },\n"
                + "  [\n    [\n      [\n        []\n      ]\n    ]\n  ],\n  {\n    \"\": []\n  }\n]\n"),
        Arguments.argumentSet(
            "pretty/object-array-object",
            "{\"a\":[{\"b\":1,\"c\":{\"d\":[2,\"e\"]}},3],\"f\":\"g\"}\n",
            "{\n  \"a\": [\n    {\n      \"b\": 1,\n      \"c\": {\n        \"d\": [\n          2,\n          \"e\"\n        ]\n      }\n    },\n    3\n  ],\n"
                + "  \"f\": \"g\"\n}\n"),
        // Recorded with no filter at all, which jq runs as '.'.
        Arguments.argumentSet(
            "pretty/no-filter", "{\"a\":[1,{\"b\":null}]}\n", "{\n  \"a\": [\n    1,\n    {\n      \"b\": null\n    }\n  ]\n}\n"),
        // ADR-0002: each key at its first position with its last value, as in compact output.
        Arguments.argumentSet(
            "pretty/duplicate-keys",
            "{\"x\":0,\"a\":1,\"b\":2,\"a\":3,\"x\":9,\"n\":{\"k\":1,\"k\":[2]},\"o\":[{\"k\":{\"j\":1},\"k\":{\"j\":2,\"j\":3}}]}\n",
            "{\n  \"x\": 9,\n  \"a\": 3,\n  \"b\": 2,\n  \"n\": {\n    \"k\": [\n      2\n    ]\n  },\n  \"o\": [\n    {\n      \"k\": {\n"
                + "        \"j\": 3\n      }\n    }\n  ]\n}\n"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("recorded")
  void prettyGivesTheCommandLinesBytes(String document, String recordedStdout) {
    assertThat(pretty(document)).isEqualTo(recordedStdout);
  }

  @Test
  void twoHundredNestedArraysIndentEachLevelByTwo() {
    // pretty.rec pretty/deep-arrays: 200 '[', 1, 200 ']' prints each '[' on its own line at two spaces a level, 1 at
    // 400 spaces, then each ']' at its '['s indent: 80 802 bytes, more than the 64 KiB print buffer.
    StringBuilder expected = new StringBuilder();
    for (int level = 0; level < 200; level++) {
      expected.append(" ".repeat(2 * level)).append("[\n");
    }
    expected.append(" ".repeat(400)).append("1\n");
    for (int level = 199; level >= 0; level--) {
      expected.append(" ".repeat(2 * level)).append("]\n");
    }
    assertThat(expected).as("the recorded length").hasSize(80_802);
    assertThat(pretty("[".repeat(200) + "1" + "]".repeat(200) + "\n")).isEqualTo(expected.toString());
  }

  @Test
  void twoHundredLevelsOfObjectsAndArraysIndentEachLevelByTwo() {
    // pretty.rec pretty/deep-mixed: 100 times {"a":[ then 1 then 100 times ]} prints each "a" key after its object's
    // '{' one level deeper, its '[' on the key's line, 1 at 400 spaces, then each closer at its opener's indent.
    StringBuilder expected = new StringBuilder("{\n");
    for (int pair = 0; pair < 100; pair++) {
      if (pair > 0) {
        expected.append(" ".repeat(4 * pair)).append("{\n");
      }
      expected.append(" ".repeat(4 * pair + 2)).append("\"a\": [\n");
    }
    expected.append(" ".repeat(400)).append("1\n");
    for (int pair = 99; pair >= 0; pair--) {
      expected.append(" ".repeat(4 * pair + 2)).append("]\n");
      expected.append(" ".repeat(4 * pair)).append("}\n");
    }
    assertThat(pretty("{\"a\":[".repeat(100) + "1" + "]}".repeat(100) + "\n")).isEqualTo(expected.toString());
  }

  @Test
  void nullOptionsThrowNullPointerException() {
    // As the other API null checks (EngineTest, ExceptionsTest): null options fail at sink, on both overloads.
    Workspace workspace = newWorkspace();
    assertThatNullPointerException()
        .as("sink(Workspace, OutputStream, null)")
        .isThrownBy(() -> JsonWriter.sink(workspace, new RecordingTarget(), null));
    assertThatNullPointerException()
        .as("sink(Workspace, WritableByteChannel, null)")
        .isThrownBy(() -> JsonWriter.sink(workspace, Channels.newChannel(new RecordingTarget()), null));
  }

  @Test
  void prettyAndCompactSinksOnOneWorkspaceKeepTheirOwnLayout() {
    // The Workspace has one printer (ADR-0027); each sink prints in its own layout, in any order of emits.
    // pretty.rec pretty/field: {"b":[1,{}],"c":"d"} prints as below; -c prints it as read.
    Workspace workspace = newWorkspace();
    RecordingTarget prettyTarget = new RecordingTarget();
    RecordingTarget compactTarget = new RecordingTarget();
    JqSink prettySink = JsonWriter.sink(workspace, prettyTarget, OutputOptions.pretty());
    JqSink compactSink = JsonWriter.sink(workspace, compactTarget, OutputOptions.compact());
    String document = "{\"b\":[1,{}],\"c\":\"d\"}";

    assertThat(compactSink.emit(read(workspace, document).root())).as("compact emit's answer").isTrue();
    assertThat(prettySink.emit(read(workspace, document).root())).as("pretty emit's answer").isTrue();
    assertThat(compactSink.emit(read(workspace, document).root())).as("second compact emit's answer").isTrue();
    assertThat(prettySink.emit(JqNull.NULL)).as("pretty emit's answer for null").isTrue();
    assertThat(prettySink.emit(JqBool.FALSE)).as("pretty emit's answer for false").isTrue();

    assertThat(prettyTarget.text()).as("the pretty target").isEqualTo("{\n  \"b\": [\n    1,\n    {}\n  ],\n  \"c\": \"d\"\n}\nnull\nfalse\n");
    assertThat(compactTarget.text()).as("the compact target").isEqualTo(document + "\n" + document + "\n");
    assertThat(prettyTarget.flushes + compactTarget.flushes).as("flush calls").isZero();
  }
}
