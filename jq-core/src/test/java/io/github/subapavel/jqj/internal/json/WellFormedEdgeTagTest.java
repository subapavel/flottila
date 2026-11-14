package io.github.subapavel.jqj.internal.json;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.internal.value.Tape;
import java.lang.foreign.MemorySegment;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0015: only an escape, DEL or a sequence that jq's {@code jvp_utf8_next} calls invalid makes a string tag 6, so a
 * string of one well-formed sequence, with no escape, at an edge of the Unicode standard's table 3-7 is tag 5: each lead's lowest and highest second byte and the
 * lowest and highest later byte. jq-ref 1.8.2 reads every one of them as one code point (observed 2026-11-14:
 * {@code map(length)} gives 1 for each and {@code map(explode[0])} gives 128, 2047, 2048, 55295, 57344, 65535, 4096,
 * 8191, 65536, 1114111, 262144 and 1048575).
 */
class WellFormedEdgeTagTest {
  private static final HexFormat HEX = HexFormat.of();

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"c280", "dfbf", "e0a080", "ed9fbf", "ee8080", "efbfbf", "e18080", "e1bfbf", "f0908080", "f48fbfbf", "f1808080", "f3bfbfbf"})
  void aWellFormedEdgeSequenceIsTagFive(String hex) {
    byte[] sequence = HEX.parseHex(hex);
    byte[] json = new byte[sequence.length + 2];
    json[0] = '"';
    System.arraycopy(sequence, 0, json, 1, sequence.length);
    json[json.length - 1] = '"';
    Document doc = new JsonReader(TapeLimits.ADR_0002).read(MemorySegment.ofArray(json));
    assertThat(Tape.tag(doc.live()[0])).as("tag of the string of %s", hex).isEqualTo(Tape.STRING);
  }
}
