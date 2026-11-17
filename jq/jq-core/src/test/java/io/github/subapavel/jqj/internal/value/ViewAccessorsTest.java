package io.github.subapavel.jqj.internal.value;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * ADR-0024: the accessors of the views {@code Workspace.read} gives (#32 conflict C4). The four {@code toJson()} are
 * the compact writer's text, and a non-ASCII string of well-formed UTF-8 is tag 5, so it reads ({@link
 * #toJsonIsCompactTextAndNonAsciiIsTag5}). A tag-6 string or key (an escape sequence, DEL or bytes that are not
 * well-formed UTF-8; ADR-0015) decodes ({@link #tag6AccessorsDecode}). {@code NumberView.doubleValue()} and {@code
 * decimalValue()} are checked by NumberViewTest and StaleViewTest.
 */
class ViewAccessorsTest {
  /**
   * One array, so that no object lookup meets a tag-6 key before its own entry: a number with a fraction, a plain
   * string, an empty array and object, a tag-6 string with an escape sequence, a tag-5 non-ASCII string, a tag-6
   * string of DEL, an object whose first key is the tag-6 spelling of its second key, and one whose second key is the
   * tag-6 spelling of its first.
   */
  private static final String DOC =
      "[1.5,\"x\",[],{},\"a\\nb\",\"\u00e9\",\"\u007f\",{\"\\u0061\":1,\"a\":2},{\"a\":1,\"\\u0061\":2}]";

  /**
   * Every accessor that meets a tag-6 string or key decodes it. jq-ref 1.8.2, observed 2026-11-12:
   * {@code printf -- '<DOC>' | jq-ref -c '(.[4], .[6] | explode), (.[7], .[8] | ., keys, length, .a)'} -> {@code
   * [97,10,98]} {@code [127]}, then for each of the two objects {@code {"a":2}} {@code ["a"]} {@code 1} {@code 2}
   * (rc 0): both spellings of a are one key, at its first position with its last value.
   */
  @TestFactory
  Stream<DynamicTest> tag6AccessorsDecode() {
    Map<String, Function<JqArray, Object>> built = new LinkedHashMap<>();
    Map<String, Object> expected = new LinkedHashMap<>();
    built.put("StringView.value() of an escape sequence", root -> string(root, 4).value());
    expected.put("StringView.value() of an escape sequence", "a\nb");
    built.put("StringView.utf8() of an escape sequence", root -> HexFormat.ofDelimiter(" ").formatHex(string(root, 4).utf8().toArray(JAVA_BYTE)));
    expected.put("StringView.utf8() of an escape sequence", "61 0a 62");
    built.put("StringView.value() of DEL", root -> string(root, 6).value());
    expected.put("StringView.value() of DEL", "\u007f");
    built.put("StringView.utf8() of DEL", root -> HexFormat.ofDelimiter(" ").formatHex(string(root, 6).utf8().toArray(JAVA_BYTE)));
    expected.put("StringView.utf8() of DEL", "7f");
    built.put("ObjectView.size() meeting a tag-6 key", root -> object(root, 7).size());
    expected.put("ObjectView.size() meeting a tag-6 key", 1);
    built.put("ObjectView.get(String) meeting a tag-6 key", root -> object(root, 7).get("a").toJson());
    expected.put("ObjectView.get(String) meeting a tag-6 key", "2");
    built.put("ObjectView.forEach(BiConsumer) meeting a tag-6 key", root -> calls(object(root, 7)));
    expected.put("ObjectView.forEach(BiConsumer) meeting a tag-6 key", List.of("a -> 2"));
    built.put("ObjectView.forEach(BiConsumer) meeting a tag-6 key after a plain one", root -> calls(object(root, 8)));
    expected.put("ObjectView.forEach(BiConsumer) meeting a tag-6 key after a plain one", List.of("a -> 2"));
    return built.entrySet().stream()
        .map(
            member ->
                dynamicTest(
                    member.getKey(),
                    () -> assertThat(member.getValue().apply(readDoc())).as(member.getKey()).isEqualTo(expected.get(member.getKey()))));
  }

  /** The calls forEach makes on {@code o}, each as {@code key -> compact text}. */
  private static List<String> calls(JqObject o) {
    List<String> calls = new ArrayList<>();
    o.forEach((k, v) -> calls.add(k + " -> " + v.toJson()));
    return calls;
  }

  /**
   * The four {@code toJson()} are the compact writer's text, and a non-ASCII string of well-formed UTF-8 is tag 5, so
   * it reads. jq-ref 1.8.2, observed 2026-11-11:
   * {@code printf '[1.5,"x",[],{},"\xc3\xa9"]' | jq-ref -c .} -> {@code [1.5,"x",[],{},"é"]} (rc 0).
   */
  @TestFactory
  Stream<DynamicTest> toJsonIsCompactTextAndNonAsciiIsTag5() {
    Map<String, Function<JqArray, Object>> built = new LinkedHashMap<>();
    Map<String, Object> expected = new LinkedHashMap<>();
    built.put("NumberView.toJson()", root -> number(root).toJson());
    expected.put("NumberView.toJson()", "1.5");
    built.put("StringView.toJson()", root -> string(root, 1).toJson());
    expected.put("StringView.toJson()", "\"x\"");
    built.put("ArrayView.toJson()", root -> view(JqArray.class, ArrayView.class, root.get(2)).toJson());
    expected.put("ArrayView.toJson()", "[]");
    built.put("ObjectView.toJson()", root -> object(root, 3).toJson());
    expected.put("ObjectView.toJson()", "{}");
    built.put("StringView.value() of non-ASCII", root -> string(root, 5).value());
    expected.put("StringView.value() of non-ASCII", "é");
    built.put("StringView.utf8() of non-ASCII", root -> HexFormat.ofDelimiter(" ").formatHex(string(root, 5).utf8().toArray(JAVA_BYTE)));
    expected.put("StringView.utf8() of non-ASCII", "c3 a9");
    return built.entrySet().stream()
        .map(
            member ->
                dynamicTest(
                    member.getKey(),
                    () -> assertThat(member.getValue().apply(readDoc())).as(member.getKey()).isEqualTo(expected.get(member.getKey()))));
  }

  /**
   * Reads {@link #DOC} into a new Workspace and returns its root array. Each dynamic test calls it itself, so the read
   * runs inside the test: the tests that read a tag-6 string then cover the reader's classification too (PIT counts a
   * test only for what runs inside it).
   */
  private static JqArray readDoc() {
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    return view(JqArray.class, ArrayView.class, workspace.read(MemorySegment.ofArray(DOC.getBytes(UTF_8))).root());
  }

  private static JqNumber number(JqArray root) {
    return view(JqNumber.class, NumberView.class, root.get(0));
  }

  private static JqString string(JqArray root, int index) {
    return view(JqString.class, StringView.class, root.get(index));
  }

  private static JqObject object(JqArray root, int index) {
    return view(JqObject.class, ObjectView.class, root.get(index));
  }

  /** Asserts that {@code value} is the view class {@code view} of the kind {@code kind}, and returns it. */
  private static <T extends JqValue> T view(Class<T> kind, Class<? extends T> view, JqValue value) {
    assertThat(value).isInstanceOf(view);
    return kind.cast(value);
  }
}
