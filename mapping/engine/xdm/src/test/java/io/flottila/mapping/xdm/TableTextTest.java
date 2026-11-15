package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code TableText} (#83, ADR-0026 "Table params"): a table param travels through the IR as one
 * {@code Literal} of the table's compact canonical JSON (ADR-0010: keys by UTF-16 code unit,
 * strings quoted as {@code JSON.stringify} quotes them, no white space), which {@code write} gives
 * and {@code read} turns back into the table. The worked examples are Node 22's {@code
 * JSON.stringify} over the keys in {@code Array.prototype.sort} order, the independent oracle; the
 * property is parameterised by seed (ADR-0018: no jqwik).
 */
class TableTextTest {

  private static SortedMap<String, String> table(String... keyThenValue) {
    SortedMap<String, String> table = new TreeMap<>();
    for (int k = 0; k < keyThenValue.length; k += 2) {
      table.put(keyThenValue[k], keyThenValue[k + 1]);
    }
    return table;
  }

  @Test
  void writeIsCompactCanonicalJson() {
    assertThat(TableText.write(table("b", "2", "a", "1"))).isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
    assertThat(TableText.write(table())).isEqualTo("{}");
    // the two-character escapes, other C0 characters as lowercase \\u, and everything else as it
    // is: DEL, U+2028, a surrogate pair, the apostrophe and the solidus
    assertThat(
            TableText.write(
                table(
                    "q\"", "\\",
                    "t\t", "\n\r\b\f\u0001\u001f\u007F",
                    "it's", "'",
                    "\uD834\uDD1E", "\uD834\uDD1E",
                    "/", "\u2028")))
        .isEqualTo(
            "{\"/\":\"\u2028\",\"it's\":\"'\",\"q\\\"\":\"\\\\\",\"t\\t\":\"\\n\\r\\b\\f\\u0001"
                + "\\u001f\u007F\",\"\uD834\uDD1E\":\"\uD834\uDD1E\"}");
  }

  /**
   * Keys by UTF-16 code unit: U+1D11E (high surrogate D834) comes before U+E000, which code point
   * order puts first, whatever order the map itself keeps.
   */
  @Test
  void keysAreOrderedByUtf16CodeUnit() {
    String expected = "{\"z\":\"a\",\"\uD834\uDD1E\":\"s\",\"\uE000\":\"p\"}";
    SortedMap<String, String> natural = table("\uE000", "p", "\uD834\uDD1E", "s", "z", "a");
    SortedMap<String, String> byCodePoint =
        new TreeMap<>(
            Comparator.comparing((String s) -> s.codePoints().toArray(), Arrays::compare));
    byCodePoint.putAll(natural);
    // witness: the two maps keep the two keys in opposite orders
    assertThat(natural.keySet()).containsExactly("z", "𝄞", "");
    assertThat(byCodePoint.keySet()).containsExactly("z", "\uE000", "\uD834\uDD1E");

    assertThat(TableText.write(natural)).isEqualTo(expected);
    assertThat(TableText.write(byCodePoint)).isEqualTo(expected);
  }

  @Test
  void readGivesTheTableOfAWorkedExample() {
    assertThat(TableText.read("{}")).isEmpty();
    assertThat(TableText.read("{\"a\":\"1\",\"b\":\"2\"}"))
        .containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
    assertThat(TableText.read("{\"it's\":\"'\",\"q\\\"\":\"\\\\\",\"t\\t\":\"\\u0001\"}"))
        .containsExactly(
            Map.entry("it's", "'"), Map.entry("q\"", "\\"), Map.entry("t\t", "\u0001"));
  }

  /** The characters a value is drawn from: the escapes' cases and a few ordinary ones. */
  private static final String[] ALPHABET = {
    "a",
    "Z",
    "0",
    " ",
    "\"",
    "\\",
    "'",
    "/",
    "{",
    "}",
    ":",
    ",",
    "\t",
    "\n",
    "\r",
    "\b",
    "\f",
    "\u0000",
    "\u0001",
    "\u001f",
    "\u007f",
    "\u00e9",
    "\u2028",
    "\uE000",
    "\uFFFD",
    "\uD834\uDD1E",
    "\uD83D\uDE00"
  };

  private static String string(SplittableRandom random) {
    StringBuilder s = new StringBuilder();
    int length = random.nextInt(9);
    for (int i = 0; i < length; i++) {
      s.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return s.toString();
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /**
   * Over 200 seeds of 10 tables each, up to 6 rows, keys and values of up to 8 characters with
   * quotation marks, backslashes, apostrophes, control characters and characters outside the BMP:
   * {@code read(write(t))} is {@code t}, writing again gives the same text, and the text holds no
   * raw control character, so it is one JSON string literal's worth of safe text.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void readOfWriteIsTheIdentity(long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    for (int n = 0; n < 10; n++) {
      SortedMap<String, String> table = new TreeMap<>();
      int rows = random.nextInt(7);
      for (int r = 0; r < rows; r++) {
        table.put(string(random), string(random));
      }

      String text = TableText.write(table);

      assertThat(TableText.read(text)).as("read of %s", text).isEqualTo(table);
      assertThat(TableText.write(TableText.read(text))).as("write again").isEqualTo(text);
      assertThat(text.chars().filter(c -> c < 0x20).count())
          .as("raw controls in %s", text)
          .isZero();
    }
  }
}
