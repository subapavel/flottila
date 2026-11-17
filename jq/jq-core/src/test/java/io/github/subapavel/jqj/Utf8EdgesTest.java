package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The edges of #33's reader and key lookup that the approved red leaves open: the leading BOM (ADR-0015 "BOM", #33
 * design section 1), a lookup key at each UTF-8 length edge, and a lead byte from F5. Exported types only.
 */
class Utf8EdgesTest {
  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  /** The bytes of {@code hex} followed by the UTF-8 bytes of {@code text}. */
  private static byte[] bytes(String hex, String text) {
    byte[] head = HEX.parseHex(hex);
    byte[] tail = text.getBytes(UTF_8);
    byte[] all = new byte[head.length + tail.length];
    System.arraycopy(head, 0, all, 0, head.length);
    System.arraycopy(tail, 0, all, head.length, tail.length);
    return all;
  }

  @Test
  void aLeadingBomIsSkippedAndNotCountedInTheColumn() {
    // jq-ref 1.8.2, observed 2026-11-11: printf '\xef\xbb\xbf{"a":1}' | jq-ref -c . -> {"a":1} (rc 0).
    Workspace workspace = newWorkspace();
    JqObject root = kind(JqObject.class, workspace.read(MemorySegment.ofArray(bytes("ef bb bf", "{\"a\":1}"))).root(), "BOM {\"a\":1}");
    assertThat(root.toJson()).as("toJson() after a BOM").isEqualTo("{\"a\":1}");
    RecordingTarget target = new RecordingTarget();
    JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(root);
    assertThat(target.text()).as("the writer's output after a BOM").isEqualTo("{\"a\":1}\n");

    // jq-ref 1.8.2, observed 2026-11-11: printf '\xef\xbb\xbf[1,2' -> "Unfinished JSON term at EOF at line 1,
    // column 4"; printf '\xef\xbb\xbf\n[1,\n2' -> "... at line 3, column 1" (rc 5 each). The design: the offset counts
    // from the segment's first byte, BOM included, here a slice two bytes into its array; EOF is the detecting byte.
    byte[] array = bytes("78 78 ef bb bf", "[1,2");
    MemorySegment slice = MemorySegment.ofArray(array).asSlice(2);
    assertThatExceptionOfType(JqSyntaxException.class)
        .as("BOM [1,2 in a slice at 2")
        .isThrownBy(() -> newWorkspace().read(slice))
        .satisfies(
            e -> {
              assertThat(e.line()).as("line").isEqualTo(1);
              assertThat(e.column()).as("column").isEqualTo(4);
              assertThat(e.offset()).as("offset of EOF from the segment's first byte").isEqualTo(7L);
            });
    // jq-ref 1.8.2, observed 2026-11-11: printf '\xef\xbb\xbf' | jq-ref -c . -> rc 0, no output; jq gives no position.
    // The BOM alone is skipped too, leaving no document, which read reports as a JqSyntaxException (ADR-0026). Only
    // its offset is pinned: EOF after the BOM is the detecting byte, at offset 3; unskipped, EF would be, at offset 0.
    assertThatExceptionOfType(JqSyntaxException.class)
        .as("the BOM alone")
        .isThrownBy(() -> newWorkspace().read(MemorySegment.ofArray(bytes("ef bb bf", ""))))
        .satisfies(e -> assertThat(e.offset()).as("offset").isEqualTo(3L));
    assertThatExceptionOfType(JqSyntaxException.class)
        .as("BOM LF [1, LF 2")
        .isThrownBy(() -> newWorkspace().read(MemorySegment.ofArray(bytes("ef bb bf", "\n[1,\n2"))))
        .satisfies(
            e -> {
              assertThat(e.line()).as("line").isEqualTo(3);
              assertThat(e.column()).as("column").isEqualTo(1);
            });
  }

  @Test
  void aPartialBomCutByEofIsNoDocumentForRead() {
    // jq-ref 1.8.2, recorded in diff/string.rec (bom-partial-1, bom-partial-2): printf '\xef' and printf '\xef\xbb' |
    // jq-ref -c . -> rc 0, no output: zero documents, which the command line now gives too (#35 answers). Workspace.read
    // reads exactly one document (ADR-0026), so at the API a partial BOM cut by EOF stays a JqSyntaxException, like
    // empty input (MalformedDocumentTest.emptyInputIsMalformed); #35 rewrote this test from "ends in the
    // malformed-input Stub", and like that test it pins only the type (spec-1 grilling item 7). Each is read alone
    // and from a slice two bytes into its array, where the bytes left count from the slice's start.
    for (String hex : new String[] {"ef", "ef bb"}) {
      assertThatExceptionOfType(JqSyntaxException.class)
          .as("a partial BOM %s alone", hex)
          .isThrownBy(() -> newWorkspace().read(MemorySegment.ofArray(bytes(hex, ""))));
      MemorySegment partial = MemorySegment.ofArray(bytes("78 78 " + hex, "")).asSlice(2);
      assertThatExceptionOfType(JqSyntaxException.class)
          .as("a partial BOM %s in a slice at 2", hex)
          .isThrownBy(() -> newWorkspace().read(partial));
    }
    // Witness: the whole BOM before a document reads.
    JqArray root = kind(JqArray.class, newWorkspace().read(MemorySegment.ofArray(bytes("ef bb bf", "[1]"))).root(), "BOM [1]");
    assertThat(root.length()).as("length of [1] after a BOM").isEqualTo(1);
  }

  @Test
  void getMatchesAKeyAtEachUtf8LengthEdge() {
    // jq-ref 1.8.2, observed 2026-11-11: printf '{"\xc2\x80":1,"\xdf\xbf":2,"\xe0\xa0\x80":3,"\xef\xbf\xbf":4,
    //   "\xf0\x90\x80\x80":5,"\xf4\x8f\xbf\xbf":6,"a":7}' | jq-ref -c '.["&#92;u0080"], .["&#92;u07ff"],
    //   .["&#92;u0800"], .["&#92;uffff"], .["&#92;ud800&#92;udc00"], .["&#92;udbff&#92;udfff"], .a, .["a\":7}x"],
    //   .["a\""], length'  (&#92; is a backslash)
    //   -> 1 2 3 4 5 6 7 null null 7 (rc 0)
    Map<String, Long> found = new LinkedHashMap<>();
    found.put("\u0080", 1L);
    found.put("߿", 2L);
    found.put("ࠀ", 3L);
    found.put("￿", 4L);
    found.put("𐀀", 5L);
    found.put("􏿿", 6L);
    found.put("a", 7L);
    StringBuilder document = new StringBuilder("{");
    found.forEach((k, v) -> document.append(document.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
    document.append('}');
    // The segment ends at the object's last byte, so a key that spells past its own entry would run off the array.
    JqObject o = kind(JqObject.class, newWorkspace().read(MemorySegment.ofArray(document.toString().getBytes(UTF_8))).root(), ".");
    for (Map.Entry<String, Long> e : found.entrySet()) {
      String hex = HEX.formatHex(e.getKey().getBytes(UTF_8));
      assertThat(kind(JqNumber.class, o.get(e.getKey()), hex).longValue()).as("get of the key %s", hex).isEqualTo(e.getValue());
    }
    assertThat(o.get("a\":7}x")).as("get of a key that spells the last entry and the bytes after it").isNull();
    assertThat(o.get("a\"")).as("get of a key that spells the last entry and its closing quote").isNull();
    assertThat(o.size()).as("size()").isEqualTo(7);
  }

  @Test
  void aLeadByteFromF5IsNotWellFormed() {
    // jq-ref 1.8.2, observed 2026-11-12: printf '["\xf5\x80\x80\x80"]' | jq-ref -c '., (.[0] | explode)' ->
    // ["����"] [65533,65533,65533,65533]: F5 is a bad lead byte and each 80 a stray continuation, one U+FFFD each. The
    // string is tag 6, and #35 decodes it (its design K3 ended the Stub this test held until then).
    JqArray root = kind(JqArray.class, newWorkspace().read(MemorySegment.ofArray(bytes("5b 22 f5 80 80 80 22 5d", ""))).root(), "f5 80 80 80");
    JqString s = kind(JqString.class, root.get(0), "f5 80 80 80");
    assertThat(s.value()).as("value() of f5 80 80 80").isEqualTo("����");
    // Witness: the four-byte sequence below F5 reads.
    JqArray f4 = kind(JqArray.class, newWorkspace().read(MemorySegment.ofArray(bytes("5b 22 f4 8f bf bf 22 5d", ""))).root(), "f4 8f bf bf");
    assertThat(kind(JqString.class, f4.get(0), "f4 8f bf bf").value()).as("value() of f4 8f bf bf").isEqualTo("􏿿");
  }
}
