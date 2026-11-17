package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.internal.value.Tape;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #35's decoding below the API (the approved design, section 1.2; ADR-0015 amendment; F-26): {@code JsonStrings}, the
 * translation of jq's {@code found_string} loop ({@code unescape}), {@code jvp_utf8_next} ({@code step}) and {@code
 * jv_string_sized} ({@code decode}). The seams, as the design names them:
 *
 * <ul>
 *   <li>{@code static int unescape(byte[] b, int from, int to, byte[] out)}: {@code b[from, to)} is a string's text
 *       between its quotes; writes the escape pass's bytes into {@code out} from 0 and returns their count, which is
 *       never more than {@code to - from};
 *   <li>{@code static int step(byte[] s, int i, int end)}: the length of the sequence at {@code i}, negative when jq's
 *       code point is -1;
 *   <li>{@code static byte[] decode(byte[] doc, long entry)}: a string entry's value, jq's string.
 * </ul>
 *
 * <p>Every jq-ref observation was taken on 2026-11-12 with {@code printf -- '<input>' | jq-ref -c <filter>}, where
 * {@code \xHH} is a raw byte.
 */
class JsonStringsTest {
  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  /** One U+FFFD, EF BF BD. */
  private static final String R = "ef bf bd";

  /** The bytes of the parts in order: a String part is its UTF-8 (ASCII here), a byte[] part as it is. */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object p : parts) {
      out.writeBytes(p instanceof byte[] b ? b : ((String) p).getBytes(UTF_8));
    }
    return out.toByteArray();
  }

  @Test
  void unescapeIsFoundStringsLoop() {
    // In the comments of this class \\u stands for one backslash and u.
    // jq-ref 1.8.2: printf -- '["\"","\\","\/","\b","\f","\n","\r","\t","\\u0000","\\u0041","\\u00e9","\\u00E9",
    //   "\\u07ff","\\u0800","\\u20AC","\\uffff","\\ud834\\udd1e","\\ud800\\udc00","\\udbff\\udfff","a\nb"]' | jq-ref -c 'map(explode)'
    //   -> [[34],[92],[47],[8],[12],[10],[13],[9],[0],[65],[233],[233],[2047],[2048],[8364],[65535],[119070],[65536],
    //      [1114111],[97,10,98]]; the bytes below are the UTF-8 of those code points.
    // The lone low surrogate and the raw bytes are the escape pass alone, before jv_string_sized replaces anything
    // (ADR-0015 amendment: "a lone low surrogate as its three bytes"; raw bytes are copied as they are).
    Map<String, String> rows = new LinkedHashMap<>();
    rows.put("\\\"", "22");
    rows.put("\\\\", "5c");
    rows.put("\\/", "2f");
    rows.put("\\b", "08");
    rows.put("\\f", "0c");
    rows.put("\\n", "0a");
    rows.put("\\r", "0d");
    rows.put("\\t", "09");
    rows.put("\\u0000", "00");
    rows.put("\\u0041", "41");
    rows.put("\\u00e9", "c3 a9");
    rows.put("\\u00E9", "c3 a9");
    rows.put("\\u07ff", "df bf");
    rows.put("\\u0800", "e0 a0 80");
    rows.put("\\u20AC", "e2 82 ac");
    rows.put("\\uffff", "ef bf bf");
    rows.put("\\ud834\\udd1e", "f0 9d 84 9e");
    rows.put("\\ud800\\udc00", "f0 90 80 80");
    rows.put("\\udbff\\udfff", "f4 8f bf bf");
    rows.put("a\\nb", "61 0a 62");
    rows.put("\\udc00", "ed b0 80");
    rows.put("\\uDFFF", "ed bf bf");
    rows.put("x\\udc00y", "78 ed b0 80 79");
    rows.put("", "");
    for (Map.Entry<String, String> row : rows.entrySet()) {
      byte[] text = row.getKey().getBytes(UTF_8);
      assertUnescape(row.getKey(), text, row.getValue());
    }
    // Raw bytes, valid or not, are copied: e2 41 and ff stay as they are until decode's second pass.
    assertUnescape("raw e2 41 ff", HEX.parseHex("e2 41 ff"), "e2 41 ff");
    assertUnescape("raw c3, then \\u00e9", bytes(HEX.parseHex("c3"), "\\u00e9"), "c3 c3 a9");
  }

  /**
   * Unescapes {@code text} placed inside {@code xx"...".."yy}: only {@code [from, to)} is read, and {@code out} is
   * exactly {@code to - from} long, so writing past the input's length fails.
   */
  private static void assertUnescape(String what, byte[] text, String expected) {
    byte[] b = bytes("xx\"", text, "\"yy");
    byte[] out = new byte[text.length];
    int n = JsonStrings.unescape(b, 3, 3 + text.length, out);
    assertThat(HEX.formatHex(Arrays.copyOf(out, n))).as("unescape of %s", what).isEqualTo(expected);
  }

  @Test
  void replacementFollowsJvpUtf8Next() {
    // Table A of the design, each row observed on jq-ref 1.8.2 with printf -- '"<bytes>"' | jq-ref -c .:
    //   80 -> one U+FFFD; c0 80 -> two; c1 bf -> two; f5 80 80 80 -> four; ff -> one (raw-ff)
    //   e0 80 80, ed a0 80, f4 90 80 80, e0 9f bf, f0 8f bf bf -> one each
    //   c2 41 -> U+FFFD A; e0 a0 41 -> U+FFFD A; e2 82 41 -> U+FFFD A
    //   e0 ff -> one; e2 41 -> one (the A is lost: cut-lead-at-end); f0 41 42 -> one; f0 9f 41 -> one; c2 -> one
    //   e2 41 42 -> U+FFFD A B (cut-lead-mid); f0 41 42 43 -> U+FFFD A B C; f0 9f 98 41 42 -> U+FFFD A B
    // So step's length is the bytes each U+FFFD took: one for a bad lead or a stray continuation, the whole sequence
    // for an overlong, surrogate or too-large one, the bytes before the first non-continuation otherwise, and every
    // byte left when the string ends first (F-26: length = end - in).
    Map<String, Integer> first = new LinkedHashMap<>();
    first.put("41", 1);
    first.put("c3 a9", 2);
    first.put("e2 82 ac", 3);
    first.put("f0 9d 84 9e", 4);
    first.put("80", -1);
    first.put("bf", -1);
    first.put("c0 80", -1);
    first.put("c1 bf", -1);
    first.put("f5 80 80 80", -1);
    first.put("ff", -1);
    first.put("e0 80 80", -3);
    first.put("ed a0 80", -3);
    first.put("e0 9f bf", -3);
    first.put("f4 90 80 80", -4);
    first.put("f0 8f bf bf", -4);
    first.put("c2 41", -1);
    first.put("e0 a0 41", -2);
    first.put("e2 82 41", -2);
    first.put("e0 ff", -2);
    first.put("e2 41", -2);
    first.put("f0 41 42", -3);
    first.put("f0 9f 41", -3);
    first.put("c2", -1);
    first.put("e2 41 42", -1);
    first.put("f0 41 42 43", -1);
    first.put("f0 9f 98 41 42", -3);
    for (Map.Entry<String, Integer> row : first.entrySet()) {
      // The sequence sits two bytes into the array and two more bytes follow end: end is the string's end, not the
      // array's, and i is not 0.
      byte[] s = bytes("zz", HEX.parseHex(row.getKey()), HEX.parseHex("80 80"));
      int end = s.length - 2;
      assertThat(JsonStrings.step(s, 2, end)).as("step at the start of %s", row.getKey()).isEqualTo(row.getValue());
    }

    // decode of a whole string entry: the escape pass, then one U+FFFD per negative step (jv_string_sized). jq-ref
    // 1.8.2, as above (here javac reads each backslash-u as its character): "\xe2\u0041" -> "\ufffd" (cut-lead-before-escape: the escape's A is cut with the lead),
    // "\xc3\u00e9" -> "\ufffdé" (bad-then-escape), "\udc00x" -> "\ufffdx" (lone-low-surrogate), "\xe0\xff" -> one
    // (i_string_truncated-utf-8 in json.rec), "é" -> "é" and "a\u0000b" -> code points [97,0,98].
    Map<byte[], String> decoded = new LinkedHashMap<>();
    decoded.put(bytes("\"", HEX.parseHex("80"), "\""), R);
    decoded.put(bytes("\"", HEX.parseHex("c0 80"), "\""), R + " " + R);
    decoded.put(bytes("\"", HEX.parseHex("c1 bf"), "\""), R + " " + R);
    decoded.put(bytes("\"", HEX.parseHex("f5 80 80 80"), "\""), R + " " + R + " " + R + " " + R);
    decoded.put(bytes("\"", HEX.parseHex("e0 80 80"), "\""), R);
    decoded.put(bytes("\"", HEX.parseHex("ed a0 80"), "\""), R);
    decoded.put(bytes("\"", HEX.parseHex("f4 90 80 80"), "\""), R);
    decoded.put(bytes("\"", HEX.parseHex("c2 41"), "\""), R + " 41");
    decoded.put(bytes("\"", HEX.parseHex("e0 a0 41"), "\""), R + " 41");
    decoded.put(bytes("\"", HEX.parseHex("e0 ff"), "\""), R);
    decoded.put(bytes("\"", HEX.parseHex("e2 41"), "\""), R);
    decoded.put(bytes("\"", HEX.parseHex("e2 41 42"), "\""), R + " 41 42");
    decoded.put(bytes("\"", HEX.parseHex("e2"), "\\u0041\""), R);
    decoded.put(bytes("\"", HEX.parseHex("c3"), "\\u00e9\""), R + " c3 a9");
    decoded.put(bytes("\"\\udc00x\""), R + " 78");
    decoded.put(bytes("\"\\ud834\\udd1e\\udc00\""), "f0 9d 84 9e " + R);
    decoded.put(bytes("\"a", HEX.parseHex("7f"), "b\""), "61 7f 62");
    decoded.put(bytes("\"a\\u0000b\""), "61 00 62");
    decoded.put(bytes("\"é\""), "c3 a9");
    decoded.put(bytes("\"\""), "");
    for (Map.Entry<byte[], String> row : decoded.entrySet()) {
      Document doc = read(row.getKey());
      long[] tape = doc.live();
      assertThat(HEX.formatHex(JsonStrings.decode(doc.bytes(), tape[0]))).as("decode of %s", HEX.formatHex(row.getKey())).isEqualTo(row.getValue());
    }
    // An entry inside a container: decode reads at the entry's offset. jq-ref: ["x","\u0041"] -> ["x","A"].
    Document doc = read(bytes("[\"x\",\"\\u0041\"]"));
    assertThat(HEX.formatHex(JsonStrings.decode(doc.bytes(), doc.live()[2]))).as("decode of the second element of [\"x\",\"\\u0041\"]").isEqualTo("41");
  }

  private static Document read(byte[] json) {
    return new JsonReader(TapeLimits.ADR_0002).read(MemorySegment.ofArray(json));
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /** Lead bytes of well-formed sequences, with the continuation range each needs second (the Unicode table 3-7). */
  private static final int[][] WELL_FORMED_LEADS = {{0xc2, 0x80, 0xbf}, {0xdf, 0x80, 0xbf}, {0xe0, 0xa0, 0xbf}, {0xed, 0x80, 0x9f}, {0xef, 0x80, 0xbf}, {0xf0, 0x90, 0xbf}, {0xf4, 0x80, 0x8f}};

  /**
   * The reader's classification (ADR-0015, #33 design C5) agrees with jq's {@code jvp_utf8_next}: a string without an
   * escape is tag 5 exactly when it holds no DEL and every {@code step} over its bytes is positive. Each seed makes up
   * to 12 bytes from ASCII, DEL, any byte from 0x80, and well-formed sequences, never a quote, a backslash or a byte
   * below 0x20.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void classifierAgreesWithJqsStep(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    int n = random.nextInt(0, 13);
    while (text.size() < n) {
      switch (random.nextInt(4)) {
        case 0 -> {
          int c;
          do {
            c = random.nextInt(0x20, 0x7f);
          } while (c == '"' || c == '\\');
          text.write(c);
        }
        case 1 -> text.write(random.nextInt(6) == 0 ? 0x7f : random.nextInt(0x80, 0x100));
        case 2 -> text.write(random.nextInt(0x80, 0x100));
        default -> {
          int[] lead = WELL_FORMED_LEADS[random.nextInt(WELL_FORMED_LEADS.length)];
          text.write(lead[0]);
          text.write(random.nextInt(lead[1], lead[2] + 1));
          int more = lead[0] < 0xe0 ? 0 : lead[0] < 0xf0 ? 1 : 2;
          for (int i = 0; i < more; i++) {
            text.write(random.nextInt(0x80, 0xc0));
          }
        }
      }
    }
    byte[] content = text.toByteArray();
    boolean plain = true;
    for (int i = 0; i < content.length; ) {
      int length = JsonStrings.step(content, i, content.length);
      assertThat(length).as("seed %d: step at %d of %s is not 0", seed, i, HEX.formatHex(content)).isNotZero();
      if (length < 0 || content[i] == 0x7f) {
        plain = false;
      }
      i += Math.abs(length);
    }
    Document doc = read(bytes("\"", content, "\""));
    assertThat(Tape.tag(doc.live()[0]))
        .as("seed %d: the tag of \"%s\"", seed, HEX.formatHex(content))
        .isEqualTo(plain ? Tape.STRING : Tape.STRING_REENCODE);
  }
}
