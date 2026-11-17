package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A UTF-8 BOM at the start of stdin under {@code -c .} (#35, ADR-0015 "Strings and BOM"; the rule sits in the input
 * reading of #38, design Open 5). The oracle runs the same cases (diff/string.cases {@code bom-*}); this module's
 * coverage counts only its own tests.
 */
class BomInputTest {
  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  @TempDir Path cwd;

  /** The outcome of one run: exit code, stdout and stderr. */
  private record Outcome(int rc, String out, String err) {}

  private Outcome run(String hex) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc = Main.run(new Invocation(List.of("-c", "."), Map.of(), cwd, new ByteArrayInputStream(HEX.parseHex(hex)), out, err));
    return new Outcome(rc, out.toString(UTF_8), err.toString(UTF_8));
  }

  @Test
  void aBomAloneOrBeforeWhitespaceOrCutByEofHoldsNoDocument() {
    // jq-ref 1.8.2, recorded in diff/string.rec (bom-only, bom-partial-1, bom-partial-2, bom-then-whitespace):
    // printf '<bytes>' | jq-ref -c . -> rc 0, no output, for each of these.
    for (String hex : List.of("ef bb bf", "ef", "ef bb", "ef bb bf 20 0a")) {
      assertThat(run(hex)).as("-c . on %s", hex).isEqualTo(new Outcome(0, "", ""));
    }
    // Witness: a document after the BOM prints (string.rec bom-document: {"a":"é"}).
    assertThat(run("ef bb bf 7b 22 61 22 3a 22 c3 a9 22 7d 0a")).as("-c . on a BOM and {\"a\":\"é\"}").isEqualTo(new Outcome(0, "{\"a\":\"é\"}\n", ""));
  }

  @Test
  void aPartialBomBeforeMoreInputIsAParseError() {
    // jq-ref 1.8.2, observed 2026-11-12: printf '\xef ', '\xef\xbb ' and '\xef\xbb{}' | jq-ref -c . -> rc 5, no output,
    // "jq: parse error: Malformed BOM" (json.rec n_structure_incomplete_UTF8_BOM for the last); and printf ' \xef\xbb\xbf1'
    // -> rc 5, "Invalid numeric literal": a BOM after whitespace is not skipped. jqj's text is the malformed-input Stub
    // until spec 5 (spec-1 grilling item 7), so only the exit code, stdout and the parse-error prefix are pinned.
    for (String hex : List.of("ef 20", "ef bb 20", "ef bb 7b 7d", "20 ef bb bf 31")) {
      Outcome o = run(hex);
      assertThat(o.rc()).as("exit code of -c . on %s", hex).isEqualTo(5);
      assertThat(o.out()).as("stdout of -c . on %s", hex).isEmpty();
      assertThat(o.err()).as("stderr of -c . on %s", hex).startsWith("jq: parse error: ");
    }
  }
}
