package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.subapavel.jqj.bench.Inputs.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 1 KB differential cases of the bench scenarios (ADR-0019, ADR-0024) in {@code oracle/.../diff/bench.cases}: jqj
 * is timed on the same work jq does only while each case's inline stdin is still its generator's 1 KB document
 * ({@code Inputs.orders}, {@code recs} or {@code ints} of {@code Size.K1}, or #85's {@code KeyProofCost.recs20} of
 * 1 024 bytes), byte for byte, and its command line is the
 * scenario's. The case file has to hold a literal copy, because {@code scripts/oracle-record.sh} records from the file;
 * this test is what ties the copy to the generator.
 */
class BenchCasesTest {

  private static final Path DIFF = Path.of("..", "..", "oracle", "src", "test", "resources", "diff");

  /**
   * The case ids with the command-line arguments (options and filter, in order) of the scenario and the document its
   * stdin holds. G3 and G4 have no 1 KB cell, so theirs is the 1 KB document of their own generator (ADR-0023).
   */
  static Stream<Arguments> cases() {
    byte[] orders = Inputs.orders(Size.K1);
    return Stream.of(
        argumentSet("bench/G1.1k", "bench/G1.1k", List.of("-c", ".a.b.c"), orders),
        argumentSet("bench/G2.1k", "bench/G2.1k", List.of("-c", Gates.G2), orders),
        argumentSet("bench/G3.1k", "bench/G3.1k", List.of("-c", Gates.G3), Inputs.recs(Size.K1)),
        argumentSet("bench/G4.1k", "bench/G4.1k", List.of("-c", Gates.G4), Inputs.ints(Size.K1)),
        argumentSet("bench/G6.compact.1k", "bench/G6.compact.1k", List.of("-c", "."), orders),
        argumentSet("bench/G6.pretty.1k", "bench/G6.pretty.1k", List.of("."), orders),
        argumentSet("bench/G7.1k", "bench/G7.1k", List.of("-c", ".a"), orders),
        argumentSet("bench/recs20-print.1k", "bench/recs20-print.1k", List.of("-c", "."), KeyProofCost.recs20(Size.K1.bytes())),
        argumentSet("bench/recs20-iterate.1k", "bench/recs20-iterate.1k", List.of("-c", ".[]|.[]"), KeyProofCost.recs20(Size.K1.bytes())));
  }

  @Test
  void theFamilyHoldsExactlyTheBenchCases() throws IOException {
    assertThat(blocks("bench.cases").keySet())
        .containsExactly(
            "bench/G1.1k",
            "bench/G6.compact.1k",
            "bench/G6.pretty.1k",
            "bench/G7.1k",
            "bench/G2.1k",
            "bench/G3.1k",
            "bench/G4.1k",
            "bench/recs20-print.1k",
            "bench/recs20-iterate.1k");
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("cases")
  void theInlineStdinIsTheGeneratorsDocumentOf1k(String id, List<String> args, byte[] document) throws IOException {
    Map<String, String> block = blocks("bench.cases").get(id);
    assertThat(block).as("case %s in bench.cases", id).isNotNull();
    assertThat(unescape(block.get("stdin"))).isEqualTo(document);
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("cases")
  void theCommandLineIsTheScenarios(String id, List<String> args, byte[] document) throws IOException {
    Map<String, String> block = blocks("bench.cases").get(id);
    assertThat(block).as("case %s in bench.cases", id).isNotNull();
    assertThat(block.get("args")).isEqualTo(String.join("\n", args));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("cases")
  void theReferenceRecordedItWithRcZero(String id, List<String> args, byte[] document) throws IOException {
    Map<String, String> block = blocks("bench.rec").get(id);
    assertThat(block).as("case %s in bench.rec", id).isNotNull();
    assertThat(block.get("rc")).isEqualTo("0");
  }

  /**
   * The blocks of a case or recording file by id: each key's value, and the {@code arg}/{@code filter} values joined by
   * LF under {@code args}. A value stays escaped.
   */
  private static Map<String, Map<String, String>> blocks(String file) throws IOException {
    Map<String, Map<String, String>> all = new LinkedHashMap<>();
    Map<String, String> current = null;
    List<String> args = new ArrayList<>();
    for (String line : Files.readAllLines(DIFF.resolve(file), UTF_8)) {
      if (line.startsWith("#") || line.isEmpty()) continue;
      int space = line.indexOf(' ');
      String key = space < 0 ? line : line.substring(0, space);
      String value = space < 0 ? "" : line.substring(space + 1);
      if (key.equals("case")) {
        current = new LinkedHashMap<>();
        args = new ArrayList<>();
        all.put(value, current);
      } else if (key.equals("end")) {
        current = null;
      } else if (current != null) {
        if (key.equals("arg") || key.equals("filter")) {
          args.add(value);
          current.put("args", String.join("\n", args));
        } else {
          current.put(key, value);
        }
      }
    }
    return all;
  }

  /** The bytes of a case-file value: {@code \n} and {@code \\} are the only escapes the bench documents need. */
  private static byte[] unescape(String value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\') {
        out.writeBytes(String.valueOf(c).getBytes(UTF_8));
        continue;
      }
      char e = value.charAt(++i);
      switch (e) {
        case 'n' -> out.write('\n');
        case '\\' -> out.write('\\');
        default -> throw new IllegalArgumentException("escape \\" + e + " is not one the bench documents have");
      }
    }
    return out.toByteArray();
  }
}
