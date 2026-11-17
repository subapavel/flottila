package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * #35's string output compared byte by byte, where a comparison of decoded Java text would hide a raw bad byte (Java's
 * decoder turns it into U+FFFD as well), and the decode scratch at exactly the print buffer's size. Below the API in
 * one place: the package-private {@code Workspace.retainedScratch()} (architect's answer to #35 Open 3).
 */
class StringOutputBytesTest {
  /** ADR-0027: the Workspace's print buffer, the most scratch a Workspace keeps. */
  private static final int BUFFER = 64 * 1024;

  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  /** The compact output bytes of the document {@code hex}, as hex. */
  private static String compact(Workspace workspace, String hex) {
    RecordingTarget target = new RecordingTarget();
    JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(workspace.read(MemorySegment.ofArray(HEX.parseHex(hex))).root());
    return HEX.formatHex(target.bytes());
  }

  @Test
  void badBytesPrintAsTheBytesOfUfffd() {
    // jq-ref 1.8.2, observed 2026-11-12 (printf -- '<bytes>' | jq-ref -c . | od -An -tx1): "\x80" -> 22 ef bf bd 22 0a.
    Workspace workspace = newWorkspace();
    assertThat(compact(workspace, "22 80 22")).as("output of \"\\x80\"").isEqualTo("22 ef bf bd 22 0a");
    // One tag-6 string (DEL makes it one) holding the first and last code point of each UTF-8 length either side of
    // the surrogates, then ED BF BF (U+DFFF, the last surrogate) and a stray 80. jq-ref 1.8.2, observed 2026-11-12:
    // -> 22 5c 75 30 30 37 66 c2 80 df bf e0 a0 80 ed 9f bf ee 80 80 ef bf bf f0 90 80 80 f4 8f bf bf ef bf bd ef bf
    // bd 22 0a; with explode -> [127,128,2047,2048,55295,57344,65535,65536,1114111,65533,65533].
    String edges = "7f c2 80 df bf e0 a0 80 ed 9f bf ee 80 80 ef bf bf f0 90 80 80 f4 8f bf bf ed bf bf 80";
    assertThat(compact(workspace, "22 " + edges + " 22"))
        .as("output of the edge sequences")
        .isEqualTo("22 5c 75 30 30 37 66 c2 80 df bf e0 a0 80 ed 9f bf ee 80 80 ef bf bf f0 90 80 80 f4 8f bf bf ef bf bd ef bf bd 22 0a");
    JqString s = kind(JqString.class, workspace.read(MemorySegment.ofArray(HEX.parseHex("22 " + edges + " 22"))).root(), "edges");
    assertThat(HEX.formatHex(s.utf8().toArray(JAVA_BYTE)))
        .as("utf8() of the edge sequences")
        .isEqualTo("7f c2 80 df bf e0 a0 80 ed 9f bf ee 80 80 ef bf bf f0 90 80 80 f4 8f bf bf ef bf bd ef bf bd");
  }

  /** {@code "} + {@code \n} {@code units} times + {@code "}: two bytes a unit between the quotes. */
  private static String escapedLfs(int units) {
    return "\"" + "\\n".repeat(units) + "\"";
  }

  @Test
  void aTag6StringOfTheBufferSizeDecodesInTheKeptScratch() {
    // jq-ref 1.8.2, observed 2026-11-12: the strings of 32 768 and 32 769 units \n (65 536 and 65 538 bytes between
    // the quotes) each print unchanged plus LF under -c . (cmp against the input).
    Workspace workspace = newWorkspace();
    for (int units : new int[] {32_768, 32_769}) {
      String json = escapedLfs(units);
      RecordingTarget target = new RecordingTarget();
      JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(read(workspace, json).root());
      assertThat(target.text()).as("output of %d units", units).isEqualTo(json + "\n");
      // Text of 64 KiB is the most the scratch takes, so it is kept; a longer one decodes into an array of its own
      // and leaves the kept scratch as it was.
      assertThat(workspace.retainedScratch()).as("the scratch kept after %d units", units).isEqualTo(BUFFER);
    }
  }
}
