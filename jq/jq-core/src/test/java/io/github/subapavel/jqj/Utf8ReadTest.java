package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reader's UTF-8 classification (ADR-0015, #33 design C5): a string is tag 5, its bytes its value and its compact
 * output, unless it holds an escape, DEL or bytes that are not well-formed UTF-8, which make it tag 6, decoded by
 * #35 with jq's U+FFFD replacement. Object keys compare by code point over UTF-8. Exported types only.
 */
class Utf8ReadTest {
  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  /** {@code ["} + bytes + {@code "]}. */
  private static byte[] inArray(byte[] bytes) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.write('[');
    b.write('"');
    b.write(bytes, 0, bytes.length);
    b.write('"');
    b.write(']');
    return b.toByteArray();
  }

  @Test
  void utf8ClassificationIsWellFormedUtf8() {
    // jq-ref 1.8.2, observed 2026-11-11: printf '["<bytes>"]' | jq-ref -c . prints each of these unchanged, and
    // each value is the code point that the UTF-8 standard gives the bytes: the lowest and highest of 2, 3 and 4
    // bytes, and both sides of the surrogate range.
    Map<String, String> wellFormed = new LinkedHashMap<>();
    wellFormed.put("c2 80", "\u0080");
    wellFormed.put("df bf", "߿");
    wellFormed.put("e0 a0 80", "ࠀ");
    wellFormed.put("ed 9f bf", "퟿");
    wellFormed.put("ee 80 80", "");
    wellFormed.put("ef bf bf", "￿");
    wellFormed.put("f0 90 80 80", "𐀀");
    wellFormed.put("f4 8f bf bf", "􏿿");
    for (Map.Entry<String, String> e : wellFormed.entrySet()) {
      byte[] bytes = HEX.parseHex(e.getKey());
      byte[] document = inArray(bytes);
      Workspace workspace = newWorkspace();
      JqArray root = kind(JqArray.class, workspace.read(MemorySegment.ofArray(document)).root(), e.getKey());
      JqString s = kind(JqString.class, root.get(0), e.getKey());
      assertThat(s.value()).as("value() of %s", e.getKey()).isEqualTo(e.getValue());
      assertThat(s.utf8().toArray(JAVA_BYTE)).as("utf8() of %s", e.getKey()).containsExactly(bytes);
      RecordingTarget target = new RecordingTarget();
      JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(root);
      assertThat(target.bytes()).as("the writer's output of [\"%s\"]", e.getKey()).containsExactly(withLf(document));
    }

    // jq-ref 1.8.2, observed 2026-11-12: printf '["<bytes>"]' | jq-ref -c '., (.[0] | explode)' gives U+FFFD (ef bf
    // bd) in their place: a stray continuation, an overlong form, a surrogate, a code point above U+10FFFF, a bad lead
    // byte, and a sequence cut by the closing quote or by an ASCII letter. They are tag 6, and #35 decodes them (its
    // design K3 ended their Stubs): 80, e0 9f bf, ed a0 80, f0 8f bf bf, f4 90 80 80, f5 and e0 a0 -> ["�"] [65533];
    // c0 80 and c1 bf -> ["��"] [65533,65533]; c2 41 and e0 a0 41 -> ["�A"] [65533,65]. No raw byte of them is written.
    Map<String, String> replaced = new LinkedHashMap<>();
    replaced.put("80", "�");
    replaced.put("c0 80", "��");
    replaced.put("c1 bf", "��");
    replaced.put("e0 9f bf", "�");
    replaced.put("ed a0 80", "�");
    replaced.put("f0 8f bf bf", "�");
    replaced.put("f4 90 80 80", "�");
    replaced.put("f5", "�");
    replaced.put("e0 a0", "�");
    replaced.put("c2 41", "�A");
    replaced.put("e0 a0 41", "�A");
    for (Map.Entry<String, String> e : replaced.entrySet()) {
      String hex = e.getKey();
      byte[] document = inArray(HEX.parseHex(hex));
      Workspace workspace = newWorkspace();
      RecordingTarget target = new RecordingTarget();
      JqSink sink = JsonWriter.sink(workspace, target, OutputOptions.compact());
      JqArray root = kind(JqArray.class, workspace.read(MemorySegment.ofArray(document)).root(), hex);
      JqString s = kind(JqString.class, root.get(0), hex);
      assertThat(s.value()).as("value() of %s", hex).isEqualTo(e.getValue());
      assertThat(s.utf8().toArray(JAVA_BYTE)).as("utf8() of %s", hex).containsExactly(e.getValue().getBytes(UTF_8));
      sink.emit(root);
      // Bytes, not text: decoding the output would turn a raw bad byte into U+FFFD too. jq-ref 1.8.2, observed
      // 2026-11-12 with od -tx1: each row's output is 5b 22, ef bf bd per U+FFFD (41 for A), 22 5d 0a.
      assertThat(target.bytes()).as("the writer's output bytes of [\"%s\"]", hex).containsExactly(("[\"" + e.getValue() + "\"]\n").getBytes(UTF_8));
    }
  }

  private static byte[] withLf(byte[] document) {
    byte[] out = new byte[document.length + 1];
    System.arraycopy(document, 0, out, 0, document.length);
    out[document.length] = '\n';
    return out;
  }

  @Test
  void getFindsANonAsciiKey() {
    // jq-ref 1.8.2, observed 2026-11-11: printf -- '{"é":1,"ab":2,"€𝄞":3,"?":4,"e":5}' |
    //   jq-ref -c '.["é"], .ab, .["€𝄞"], .["?"], .e, length, keys_unsorted'
    //   -> 1 2 3 4 5 5 ["é","ab","€𝄞","?","e"]   (rc 0)
    JqObject o = kind(JqObject.class, read(newWorkspace(), "{\"é\":1,\"ab\":2,\"€𝄞\":3,\"?\":4,\"e\":5}").root(), ".");
    Map<String, Long> found = new LinkedHashMap<>();
    found.put("é", 1L);
    found.put("ab", 2L);
    found.put("€𝄞", 3L);
    found.put("?", 4L);
    found.put("e", 5L);
    for (Map.Entry<String, Long> e : found.entrySet()) {
      assertThat(kind(JqNumber.class, o.get(e.getKey()), e.getKey()).longValue()).as("get(%s)", e.getKey()).isEqualTo(e.getValue());
    }
    // A lone surrogate is no code point, so it never matches; encoded to UTF-8 it would become "?" and find 4.
    for (String key : List.of("\ud834", "\udd1e", "€\ud834", "\ud834€")) {
      assertThat(o.get(key)).as("get of a key with a lone surrogate, %s", HEX.formatHex(key.getBytes(UTF_8))).isNull();
    }
    assertThat(o.size()).as("size()").isEqualTo(5);
    List<String> keys = new ArrayList<>();
    o.forEach((k, v) -> keys.add(k));
    assertThat(keys).as("forEach's keys").containsExactly("é", "ab", "€𝄞", "?", "e");
  }
}
