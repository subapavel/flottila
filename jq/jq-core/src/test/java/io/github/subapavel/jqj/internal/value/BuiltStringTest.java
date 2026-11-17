package io.github.subapavel.jqj.internal.value;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A string built at run time (#34 design section 7): {@code BuiltString.of(String)} is a {@link JqString} whose
 * {@code value()} is the text and whose {@code utf8()} is a read-only slice of its UTF-8 bytes. {@code toJson()} is
 * jq's compact text since #35.
 */
class BuiltStringTest {
  @Test
  void valueAndUtf8RoundTrip() {
    // One char of each UTF-8 length: U+0041, U+00E9, U+20AC, U+1D11E (a surrogate pair in Java), with SP and a quote.
    String text = "A é€𝄞\"";
    JqString s = BuiltString.of(text);

    assertThat(s.value()).as("value()").isEqualTo(text);
    MemorySegment utf8 = s.utf8();
    // The bytes, written out: 41 20, C3 A9, E2 82 AC, F0 9D 84 9E, 22.
    assertThat(HexFormat.ofDelimiter(" ").formatHex(utf8.toArray(JAVA_BYTE))).as("utf8() bytes").isEqualTo("41 20 c3 a9 e2 82 ac f0 9d 84 9e 22");
    assertThat(utf8.isReadOnly()).as("utf8() is read-only").isTrue();

    // jq's error texts are ASCII: the one #34's Field builds reads back whole.
    String error = "Cannot index array with string (\"abcdefghijklmnopqrstuvwx...\")";
    assertThat(BuiltString.of(error).value()).as("value() of an error text").isEqualTo(error);
    assertThat(BuiltString.of(error).utf8().byteSize()).as("utf8() length of an error text").isEqualTo(62);

    // The empty string: no bytes.
    assertThat(BuiltString.of("").value()).as("value() of the empty string").isEmpty();
    assertThat(BuiltString.of("").utf8().byteSize()).as("utf8() length of the empty string").isZero();
  }

  @Test
  void toJsonIsJqsCompactText() {
    // #35 ended this entry's Stub (design K3): toJson() is Printer.json(BuiltString), jq's jvp_dump_string. jq-ref
    // 1.8.2, observed 2026-11-12: jq-ref -n -c '"a", "", "x\"y\\z/", "\n\t\r\b\f", "\u0000\u0001\u001f\u007f",
    // "é€𝄞"' (backslash-u written as in jq) -> "a"  ""  "x\"y\\z/"  "\n\t\r\b\f"  "\u0000\u0001\u001f\u007f"  "é€𝄞".
    Map<String, String> rows = new LinkedHashMap<>();
    rows.put("a", "\"a\"");
    rows.put("", "\"\"");
    rows.put("x\"y\\z/", "\"x\\\"y\\\\z/\"");
    rows.put("\n\t\r\b\f", "\"\\n\\t\\r\\b\\f\"");
    rows.put("\u0000\u0001\u001f\u007f", "\"\\u0000\\u0001\\u001f\\u007f\"");
    rows.put("é€𝄞", "\"é€𝄞\"");
    for (Map.Entry<String, String> row : rows.entrySet()) {
      assertThat(BuiltString.of(row.getKey()).toJson()).as("toJson() of %s", row.getValue()).isEqualTo(row.getValue());
    }
  }

  @Test
  void sliceAccessorsDescribeTheUtf8Bytes() {
    // #35 design 1.5: the Printer reads a built string through bytes() and byteLength(), the UTF-8 slice of
    // BuiltString.of's text from index 0: C3 A9 for é, E2 82 AC for €.
    BuiltString s = BuiltString.of("é€");
    assertThat(s.byteLength()).as("byteLength() of é€").isEqualTo(5);
    assertThat(HexFormat.ofDelimiter(" ").formatHex(Arrays.copyOfRange(s.bytes(), 0, s.byteLength())))
        .as("bytes()[0, byteLength()) of é€")
        .isEqualTo("c3 a9 e2 82 ac");
  }
}
