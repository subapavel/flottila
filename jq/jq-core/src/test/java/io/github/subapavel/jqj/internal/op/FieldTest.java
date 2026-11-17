package io.github.subapavel.jqj.internal.op;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * jq's {@code .name} as an operation (#34 design section 6): {@code new Field(name)} is an {@code Fn0}. On an object
 * it gives the last value of the key, or {@code JqNull.NULL} when there is none; on null it gives null; on any other
 * kind it throws a {@link FilterException} whose value is a string holding jq's text. Every expected text is jq-ref
 * 1.8.2's, recorded in {@code oracle/src/test/resources/diff/field.rec}.
 */
class FieldTest {

  /** The root of {@code json}, read into a Workspace of its own, so no other read makes it stale. */
  private static JqValue root(String json) {
    return JqEngine.builder().build().newWorkspace().read(MemorySegment.ofArray(json.getBytes(UTF_8))).root();
  }

  /** The text of the error {@code .name} raises on {@code json}. */
  private static String errorText(String name, String json) {
    JqValue in = root(json);
    FilterException e =
        assertThatExceptionOfType(FilterException.class)
            .as(".%s on %s", name, json)
            .isThrownBy(() -> new Field(name).apply(new RunState(), in))
            .actual();
    assertThat(e.value()).as("the error value of .%s on %s", name, json).isInstanceOf(JqString.class);
    return ((JqString) e.value()).value();
  }

  @Test
  void lastValueWins() {
    // jq-ref 1.8.2, recorded (field/duplicate-field): .a on {"a":1,"b":2,"a":3} -> 3.
    JqValue object = root("{\"a\":1,\"b\":2,\"a\":3}");
    assertThat(((JqValue) new Field("a").apply(new RunState(), object)).toJson()).as(".a on {\"a\":1,\"b\":2,\"a\":3}").isEqualTo("3");
    assertThat(((JqValue) new Field("b").apply(new RunState(), object)).toJson()).as(".b on {\"a\":1,\"b\":2,\"a\":3}").isEqualTo("2");

    // A container value: the result is the view JqObject.get gives (ADR-0026), the last one.
    JqValue nested = root("{\"a\":{\"x\":1},\"a\":[3,{}]}");
    Object a = new Field("a").apply(new RunState(), nested);
    assertThat(((JqValue) a).toJson()).as(".a on {\"a\":{\"x\":1},\"a\":[3,{}]}").isEqualTo("[3,{}]");
    assertThat(a).as("the same view as get(\"a\")").isEqualTo(((JqObject) nested).get("a"));
  }

  @Test
  void missingKeyAndNullGiveJqNull() {
    // jq-ref 1.8.2, recorded: field/miss-key, field/miss-empty and field/null-input each print null (rc 0).
    assertThat(new Field("a").apply(new RunState(), root("{\"b\":1}"))).as(".a on {\"b\":1}").isSameAs(JqNull.NULL);
    assertThat(new Field("a").apply(new RunState(), root("{}"))).as(".a on {}").isSameAs(JqNull.NULL);
    assertThat(new Field("a").apply(new RunState(), JqNull.NULL)).as(".a on null").isSameAs(JqNull.NULL);
    // A key that is there with the value null gives that null too (field/hit-null).
    assertThat(new Field("a").apply(new RunState(), root("{\"a\":null}"))).as(".a on {\"a\":null}").isSameAs(JqNull.NULL);
    // Witness: a key that is there gives its value, not null.
    assertThat(((JqValue) new Field("b").apply(new RunState(), root("{\"b\":1}"))).toJson()).as("witness: .b on {\"b\":1}").isEqualTo("1");
  }

  @Test
  void otherKindsRaiseJqsText() {
    // jq-ref 1.8.2, recorded (field/error-{array,empty-array,string,number,true,false}), each with rc 5:
    //   jq: error (at <stdin>:1): Cannot index array with string ("a")
    //   jq: error (at <stdin>:1): Cannot index string with string ("a")
    //   jq: error (at <stdin>:1): Cannot index number with string ("a")
    //   jq: error (at <stdin>:1): Cannot index boolean with string ("a")   (true and false alike)
    Map<String, String> texts = new LinkedHashMap<>();
    texts.put("[1]", "Cannot index array with string (\"a\")");
    texts.put("[]", "Cannot index array with string (\"a\")");
    texts.put("\"s\"", "Cannot index string with string (\"a\")");
    texts.put("5", "Cannot index number with string (\"a\")");
    texts.put("true", "Cannot index boolean with string (\"a\")");
    texts.put("false", "Cannot index boolean with string (\"a\")");
    for (Map.Entry<String, String> e : texts.entrySet()) {
      assertThat(errorText("a", e.getKey())).as("the text of .a on %s", e.getKey()).isEqualTo(e.getValue());
    }
    // field/chain-error-middle: .c on the number 5 -> Cannot index number with string ("c").
    assertThat(errorText("c", "5")).as("the text of .c on 5").isEqualTo("Cannot index number with string (\"c\")");
  }

  @Test
  void nameDumpCutsAtTheRecordedBoundary() {
    // jq-ref 1.8.2, recorded (field/error-name-N, .<N bytes> on [1], rc 5): a name of up to 27 bytes is printed whole
    // in quotes; from 28 bytes on, the first 24 bytes and "...", still in quotes.
    String letters = "abcdefghijklmnopqrstuvwxyz0123";
    Map<Integer, String> texts = new LinkedHashMap<>();
    texts.put(24, "Cannot index array with string (\"abcdefghijklmnopqrstuvwx\")");
    texts.put(25, "Cannot index array with string (\"abcdefghijklmnopqrstuvwxy\")");
    texts.put(27, "Cannot index array with string (\"abcdefghijklmnopqrstuvwxyz0\")");
    texts.put(28, "Cannot index array with string (\"abcdefghijklmnopqrstuvwx...\")");
    texts.put(29, "Cannot index array with string (\"abcdefghijklmnopqrstuvwx...\")");
    texts.put(30, "Cannot index array with string (\"abcdefghijklmnopqrstuvwx...\")");
    for (Map.Entry<Integer, String> e : texts.entrySet()) {
      String name = letters.substring(0, e.getKey());
      assertThat(errorText(name, "[1]")).as("the text of a %d-byte name on [1]", e.getKey()).isEqualTo(e.getValue());
    }
  }
}
