package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The writer's string arms (#35 design, section 1.4 and 1.5; ADR-0015 "Writer"): a tag-6 string is decoded and
 * re-escaped as jq's {@code jvp_dump_string}, keys included; a built string prints the same way and needs no document;
 * a long tag-6 string drains and checks its document (ADR-0027). Below the API in one place, {@link
 * #scratchStaysBounded}: the package-private {@code int Workspace.retainedScratch()}, the byte length of the decode
 * scratch the Workspace's printer keeps after a result (architect's answer to Open 3: at most 64 KiB, the print
 * buffer's size).
 *
 * <p>Every jq-ref observation was taken on 2026-11-12 with {@code printf -- '<input>' | jq-ref -c .}, where {@code
 * \xHH} is a raw byte, or is a recording of {@code diff/string.rec}.
 */
class PrinterStringTest {
  /** ADR-0027: the Workspace's print buffer. */
  private static final int BUFFER = 64 * 1024;

  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  /** The bytes of the parts in order: a String part is its UTF-8, a byte[] part as it is. */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object p : parts) {
      out.writeBytes(p instanceof byte[] b ? b : ((String) p).getBytes(UTF_8));
    }
    return out.toByteArray();
  }

  /** The compact output of the document {@code json} through a fresh Workspace's OutputStream sink. */
  private static String compact(byte[] json) {
    Workspace workspace = newWorkspace();
    RecordingTarget target = new RecordingTarget();
    JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(workspace.read(MemorySegment.ofArray(json)).root());
    return target.text();
  }

  @Test
  void tag6PrintsAsJvpDumpString() {
    Map<byte[], String> rows = new LinkedHashMap<>();
    // jq-ref 1.8.2: each one-unit string in an array:
    //   ["\u0041","\/","\n","\"","\\","\b","\f","\r","\t","\u00E9","\u0001","\u001F","\u007f","<DEL>","\ud834\udd1e",
    //    "\udc00","\uFFFE","\x80","\xff","\xc0\x80","\xed\xa0\x80","\xf4\x90\x80\x80","\xe0\x80\x80"]
    //   -> ["A","/","\n","\"","\\","\b","\f","\r","\t","é","\u0001","\u001f","\u007f","\u007f","𝄞","�","￾","�","�","��","�","�","�"]
    rows.put(
        bytes(
            "[\"\\u0041\",\"\\/\",\"\\n\",\"\\\"\",\"\\\\\",\"\\b\",\"\\f\",\"\\r\",\"\\t\",\"\\u00E9\",\"\\u0001\",\"\\u001F\",\"\\u007f\",\"",
            HEX.parseHex("7f"),
            "\",\"\\ud834\\udd1e\",\"\\udc00\",\"\\uFFFE\",\"",
            HEX.parseHex("80"),
            "\",\"",
            HEX.parseHex("ff"),
            "\",\"",
            HEX.parseHex("c0 80"),
            "\",\"",
            HEX.parseHex("ed a0 80"),
            "\",\"",
            HEX.parseHex("f4 90 80 80"),
            "\",\"",
            HEX.parseHex("e0 80 80"),
            "\"]"),
        "[\"A\",\"/\",\"\\n\",\"\\\"\",\"\\\\\",\"\\b\",\"\\f\",\"\\r\",\"\\t\",\"é\",\"\\u0001\",\"\\u001f\",\"\\u007f\",\"\\u007f\",\"𝄞\",\"\ufffd\",\"\ufffe\",\"\ufffd\",\"\ufffd\",\"\ufffd\ufffd\",\"\ufffd\",\"\ufffd\",\"\ufffd\"]");
    // string.rec: mixed-escapes, all-escapes, controls, lone-low-surrogate, bad-then-escape, cut-lead-at-end,
    // cut-lead-before-escape, cut-lead-mid, raw-del, raw-ff.
    rows.put(bytes("\"é\\/x\\u007f\""), "\"é/x\\u007f\"");
    rows.put(
        bytes("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\u001F\\u0020\\u00e9\\u20AC\\uFFFE\\ud834\\uDD1E\""),
        "\"\\\"\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f é€\ufffe𝄞\"");
    rows.put(bytes("\"\\u0001\\u000b\\u001b\""), "\"\\u0001\\u000b\\u001b\"");
    rows.put(bytes("\"\\udc00x\""), "\"\ufffdx\"");
    rows.put(bytes("\"", HEX.parseHex("c3"), "\\u00e9\""), "\"\ufffdé\"");
    rows.put(bytes("\"", HEX.parseHex("e2"), "A\""), "\"\ufffd\"");
    rows.put(bytes("\"", HEX.parseHex("e2"), "\\u0041\""), "\"\ufffd\"");
    rows.put(bytes("\"", HEX.parseHex("e2"), "AB\""), "\"\ufffdAB\"");
    rows.put(bytes("\"a", HEX.parseHex("7f"), "b\""), "\"a\\u007fb\"");
    rows.put(bytes("\"", HEX.parseHex("ff"), "\""), "\"\ufffd\"");
    // jq-ref 1.8.2: "\x80\u0041\xff\xc0\x80\ud834\udd1e\xed\xa0\x80\udc00\xe0\x80\x80<DEL>\xf4\x90\x80\x80\u00E9\x80
    //   \udc00\udc00\xff\n" -> "�A���𝄞���\u007f�é����\n": the units above are context-free side by side.
    rows.put(
        bytes(
            "\"",
            HEX.parseHex("80"),
            "\\u0041",
            HEX.parseHex("ff c0 80"),
            "\\ud834\\udd1e",
            HEX.parseHex("ed a0 80"),
            "\\udc00",
            HEX.parseHex("e0 80 80 7f f4 90 80 80"),
            "\\u00E9",
            HEX.parseHex("80"),
            "\\udc00\\udc00",
            HEX.parseHex("ff"),
            "\\n\""),
        "\"\ufffdA\ufffd\ufffd\ufffd𝄞\ufffd\ufffd\ufffd\\u007f\ufffdé\ufffd\ufffd\ufffd\ufffd\\n\"");
    for (Map.Entry<byte[], String> row : rows.entrySet()) {
      String input = HEX.formatHex(row.getKey());
      assertThat(compact(row.getKey())).as("the writer's output of %s", input).isEqualTo(row.getValue() + "\n");
      JqValue root = newWorkspace().read(MemorySegment.ofArray(row.getKey())).root();
      assertThat(root.toJson()).as("toJson() of %s", input).isEqualTo(row.getValue());
    }
  }

  @Test
  void tag6KeysPrintDecoded() {
    Map<byte[], String> rows = new LinkedHashMap<>();
    // jq-ref 1.8.2: {"\u00e9":1,"\u0061\u0062":2,"\udfaa":3,"\xff":4,"é":5} -> {"é":5,"ab":2,"�":4}: a key decodes
    // before it is compared, so "\u00e9" and "é" are one key, and so are "\udfaa" and "\xff" (both U+FFFD).
    rows.put(bytes("{\"\\u00e9\":1,\"\\u0061\\u0062\":2,\"\\udfaa\":3,\"", HEX.parseHex("ff"), "\":4,\"é\":5}"), "{\"é\":5,\"ab\":2,\"\ufffd\":4}");
    // jq-ref 1.8.2: {"\n\u0001":[{"\u007f":"\/"}]} -> {"\n\u0001":[{"\u007f":"/"}]}
    rows.put(bytes("{\"\\n\\u0001\":[{\"\\u007f\":\"\\/\"}]}"), "{\"\\n\\u0001\":[{\"\\u007f\":\"/\"}]}");
    // jq-ref 1.8.2 (ViewAccessorsTest's document): {"\u0061":1,"a":2} -> {"a":2}; string.rec escaped-duplicate-key:
    // {"a":1,"\u0061":2} -> {"a":2}.
    rows.put(bytes("{\"\\u0061\":1,\"a\":2}"), "{\"a\":2}");
    rows.put(bytes("{\"a\":1,\"\\u0061\":2}"), "{\"a\":2}");
    for (Map.Entry<byte[], String> row : rows.entrySet()) {
      String input = HEX.formatHex(row.getKey());
      assertThat(compact(row.getKey())).as("the writer's output of %s", input).isEqualTo(row.getValue() + "\n");
      JqValue root = newWorkspace().read(MemorySegment.ofArray(row.getKey())).root();
      assertThat(root.toJson()).as("toJson() of %s", input).isEqualTo(row.getValue());
    }
  }

  /** {@code "} + {@code a\n} {@code units} times + {@code "}: 3 bytes a unit, 2 decoded, 3 printed. */
  private static String escapedLines(int units) {
    return "\"" + "a\\n".repeat(units) + "\"";
  }

  @Test
  void longTag6StringDrainsAndChecksTheDocument() {
    // jq-ref 1.8.2, observed 2026-11-12: the 90 002-byte string of 30 000 units a\n prints unchanged plus LF under
    // -c . (cmp against the input), more than one 64 KiB print buffer.
    String big = escapedLines(30_000);
    Workspace workspace = newWorkspace();

    // Witness: with no read in between, every byte arrives, in more than one write.
    RecordingTarget whole = new RecordingTarget();
    assertThat(JsonWriter.sink(workspace, whole, OutputOptions.compact()).emit(read(workspace, big).root())).as("emit's answer").isTrue();
    assertThat(whole.text()).as("output of the 90 002-byte tag-6 string").isEqualTo(big + "\n");
    assertThat(whole.writes).as("write calls for a 90 003-byte result").isGreaterThan(1);

    // The target reads another document into the same Workspace during its first write (ADR-0027).
    List<JsonDoc> readInside = new ArrayList<>();
    RecordingTarget reading =
        new RecordingTarget(
            n -> {
              if (n == 1) readInside.add(read(workspace, "[7]"));
            });
    JqSink sink = JsonWriter.sink(workspace, reading, OutputOptions.compact());
    JqValue root = read(workspace, big).root();
    assertStale(() -> sink.emit(root), "emit of the long tag-6 string after its target read [7]");
    assertThat(reading.writes).as("write calls after the read inside the first one").isEqualTo(1);
    assertThat(reading.text()).as("bytes written before the document went stale").isNotEmpty();
    assertThat(big).as("the bytes written are a prefix of the result").startsWith(reading.text());
    assertThat(readInside).as("documents read inside the write").hasSize(1);
  }

  @Test
  void longBuiltStringPrintsAfterItsDocumentDropped() {
    // jq-ref 1.8.2, observed 2026-11-12: jq-ref -n -c '"a\n" * 40000' prints "a\na\n…" (40 000 units of the six
    // characters a, backslash, n), 120 003 bytes with the quotes and the LF.
    String text = "a\n".repeat(40_000);
    String expected = "\"" + "a\\n".repeat(40_000) + "\"";
    Workspace workspace = newWorkspace();
    RecordingTarget target = new RecordingTarget();
    JqSink sink = JsonWriter.sink(workspace, target, OutputOptions.compact());

    // The printer printed a document's value and the document was then closed: a built string has no document, so
    // the drains of its 120 003 bytes must not check the closed one.
    JsonDoc doc = read(workspace, "[1]");
    assertThat(sink.emit(doc.root())).as("emit's answer for [1]").isTrue();
    doc.close();
    assertThat(sink.emit(BuiltString.of(text))).as("emit's answer for the built string").isTrue();
    assertThat(target.text()).as("bytes written for [1] and the built string").isEqualTo("[1]\n" + expected + "\n");
    assertThat(BuiltString.of(text).toJson()).as("toJson() of the built string").isEqualTo(expected);
  }

  @Test
  void scratchStaysBounded() {
    // Architect's answer to Open 3: the Workspace keeps a scratch of at most 64 KiB, the print buffer's size; a
    // longer tag-6 string decodes into a buffer for that call only. jq-ref 1.8.2, observed 2026-11-12: the
    // 1 572 866-byte string of 524 288 units a\n (1 MiB decoded) and the object {"<the same as a key>":1}
    // (1 572 870 bytes) each print unchanged plus LF under -c . (cmp against the input).
    String mib = escapedLines(524_288);
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("a 1 MiB tag-6 string", mib);
    cases.put("a 1 MiB tag-6 key", "{" + mib + ":1}");
    Workspace workspace = newWorkspace();
    for (Map.Entry<String, String> c : cases.entrySet()) {
      String what = c.getKey();
      String json = c.getValue();
      RecordingTarget target = new RecordingTarget();
      JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(read(workspace, json).root());
      assertThat(target.text()).as("output of %s", what).isEqualTo(json + "\n");
      assertThat(workspace.retainedScratch()).as("the scratch the Workspace keeps after printing %s", what).isLessThanOrEqualTo(BUFFER);
    }
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /** Code points from every printing class: plain, short escapes, other controls, DEL, 2-, 3- and 4-byte UTF-8. */
  private static final int[] POOL = {
    'a', 'Z', ' ', '"', '\\', '/', 0x00, 0x01, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1b, 0x1f, 0x7f, 0x80, 0xe9, 0x7ff, 0x800,
    0x20ac, 0xd7ff, 0xe000, 0xfffd, 0xfffe, 0xffff, 0x10000, 0x1d11e, 0x10ffff
  };

  private static final Map<Integer, String> SHORT = Map.of('"' + 0, "\\\"", '\\' + 0, "\\\\", '/' + 0, "\\/", 0x08, "\\b", 0x0c, "\\f", 0x0a, "\\n", 0x0d, "\\r", 0x09, "\\t");

  /**
   * A built string prints as a view of the same text (design 1.5): each seed draws up to 10 code points from {@link
   * #POOL}, spells them as a JSON string with random escapes (raw where JSON allows it, a short escape, or {@code
   * \\uXXXX} in either case, a surrogate pair above U+FFFF), and reads it. The view's value is the drawn text, the
   * built string's text is the view's, and the printed text reads back to the same value.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void builtStringPrintsAsItsView(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    StringBuilder text = new StringBuilder();
    StringBuilder json = new StringBuilder("\"");
    int n = random.nextInt(0, 11);
    for (int i = 0; i < n; i++) {
      int cp = POOL[random.nextInt(POOL.length)];
      text.appendCodePoint(cp);
      boolean raw = cp >= 0x20 && cp != '"' && cp != '\\';
      int how = random.nextInt(3);
      if (raw && how == 0) {
        json.appendCodePoint(cp);
      } else if (SHORT.containsKey(cp) && how == 1) {
        json.append(SHORT.get(cp));
      } else if (cp >= 0x10000) {
        json.append(u(Character.highSurrogate(cp), random)).append(u(Character.lowSurrogate(cp), random));
      } else {
        json.append(u((char) cp, random));
      }
    }
    json.append('"');
    Workspace workspace = newWorkspace();
    JqString view = kind(JqString.class, read(workspace, json.toString()).root(), json.toString());
    assertThat(view.value()).as("seed %d: value() of %s", seed, json).isEqualTo(text.toString());
    String printed = view.toJson();
    assertThat(BuiltString.of(text.toString()).toJson()).as("seed %d: toJson() of the built string of %s", seed, json).isEqualTo(printed);
    RecordingTarget target = new RecordingTarget();
    JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(BuiltString.of(text.toString()));
    assertThat(target.text()).as("seed %d: the writer's output of the built string of %s", seed, json).isEqualTo(printed + "\n");
    JqString again = kind(JqString.class, read(newWorkspace(), printed).root(), printed);
    assertThat(again.value()).as("seed %d: value() of the printed text %s", seed, printed).isEqualTo(text.toString());
  }

  /** {@code \\u} and the four hex digits of {@code c}, each digit in lower or upper case at random. */
  private static String u(char c, RandomGenerator random) {
    String hex = String.format("%04x", (int) c);
    return "\\u" + (random.nextBoolean() ? hex : hex.toUpperCase(java.util.Locale.ROOT));
  }
}
