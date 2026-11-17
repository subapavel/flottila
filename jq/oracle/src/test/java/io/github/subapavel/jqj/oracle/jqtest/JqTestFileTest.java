package io.github.subapavel.jqj.oracle.jqtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The jqtest parser, as jq_test.c's run_jq_tests reads a file (ADR-0020), and the case ids. */
class JqTestFileTest {

  private static List<JqTestCase> parse(String text) {
    return JqTestFile.parse("t.test", text);
  }

  @Test
  void theSevenFilesOfJq182HoldTheCasesJqCounts() {
    Map<String, Integer> counts = JqTestCorpus.countsPerFile();

    assertThat(counts)
        .containsExactly(
            Map.entry("base64.test", 10),
            Map.entry("jq.test", 550),
            Map.entry("man.test", 231),
            Map.entry("manonig.test", 19),
            Map.entry("onig.test", 47),
            Map.entry("optional.test", 2),
            Map.entry("uri.test", 20));
    assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(879);
  }

  @Test
  void aCaseIsTheProgramTheRawInputLineAndTheExpectedLinesUpToASkipLine() {
    List<JqTestCase> cases = parse("# comment\n\n.[]\n[1,2]\n1\n2\n\n.\n#not a comment: the input is raw\n");

    assertThat(cases).hasSize(2);
    JqTestCase first = cases.get(0);
    assertThat(first.mode()).isEqualTo(JqTestCase.Mode.RESULTS);
    assertThat(first.line()).isEqualTo(3);
    assertThat(first.program()).isEqualTo(".[]");
    assertThat(first.input()).isEqualTo("[1,2]");
    assertThat(first.expected()).containsExactly("1", "2");
    assertThat(cases.get(1).input()).isEqualTo("#not a comment: the input is raw");
    assertThat(cases.get(1).expected()).isEmpty();
  }

  @Test
  void aSkipLineIsBlankOrACommentAfterSpacesAndTabs() {
    List<JqTestCase> cases = parse(" \t# indented comment\n \t\n.\n1\n1\n \t# ends the expected lines\n.\n2\n2\n \t\n");

    assertThat(cases).extracting(JqTestCase::line).containsExactly(3, 7);
    assertThat(cases).extracting(JqTestCase::expected).containsExactly(List.of("1"), List.of("2"));
  }

  @Test
  void theExpectedLinesRunToTheEndOfTheFile() {
    assertThat(parse(".\n1\n1").get(0).expected()).containsExactly("1");
  }

  @Test
  void aFailCaseOnlyCompilesItsLinesAfterTheProgramAreTheExpectedMessage() {
    List<JqTestCase> cases = parse("%%FAIL\n\n{\njq: error: one\nmore\n\n%%FAIL IGNORE MSG\n}\nanything\n");

    assertThat(cases).hasSize(2);
    JqTestCase fail = cases.get(0);
    assertThat(fail.mode()).isEqualTo(JqTestCase.Mode.FAIL);
    assertThat(fail.line()).isEqualTo(1);
    assertThat(fail.program()).isEqualTo("{");
    assertThat(fail.input()).isNull();
    assertThat(fail.expected()).containsExactly("jq: error: one", "more");
    assertThat(cases.get(1).mode()).isEqualTo(JqTestCase.Mode.FAIL_IGNORE_MSG);
    assertThat(cases.get(1).expected()).containsExactly("anything");
  }

  @Test
  void theMarkersAreExactLines() {
    List<JqTestCase> cases = parse("%%FAIL \n1\n1\n\n%%FAIL IGNORE\n1\n1\n");

    assertThat(cases).extracting(JqTestCase::mode).containsExactly(JqTestCase.Mode.RESULTS, JqTestCase.Mode.RESULTS);
    assertThat(cases).extracting(JqTestCase::program).containsExactly("%%FAIL ", "%%FAIL IGNORE");
  }

  @Test
  void aProgramWithoutAnInputLineIsRejected() {
    assertThatThrownBy(() -> parse(".\n1\n1\n\n.[]\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("t.test:5: the program has no input line");
  }

  @Test
  void aLineOf4096BytesOrMoreIsRejectedAsJqsFgetsWouldSplitIt() {
    // 4094 bytes and an LF, or 4095 bytes at the end of the file, are one fgets.
    String fits = "\"" + "a".repeat(4092) + "\"";
    assertThat(parse(".\n" + fits + "\n" + fits + "\n").get(0).input()).hasSize(4094);
    String lastFits = "\"" + "a".repeat(4093) + "\"";
    assertThat(parse(".\n1\n" + lastFits).get(0).expected()).containsExactly(lastFits);

    // 4095 bytes (2046 two-byte characters among them) and an LF are 4096.
    String tooLong = "\"" + "é".repeat(2046) + "a\"";
    assertThatThrownBy(() -> parse(".\n1\n" + tooLong + "\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("t.test:3: the line is 4096 bytes; jq's test runner reads at most 4095");
  }

  @Test
  void theIdIsTheFileAndTwelveHexDigitsOfTheSha256OfTheCaseLinesMarkerIncluded() {
    List<JqTestCase> cases = parse("%%FAIL\n{\njq: error: x\n\n.\nnull\nnull\n");

    // printf '%%%%FAIL\n{\njq: error: x' | sha256sum; printf '.\nnull\nnull' | sha256sum
    assertThat(cases).extracting(JqTestCase::id).containsExactly("t.test#a2259f2feb7e", "t.test#26f85533bd77");
  }

  @Test
  void anIdSurvivesInsertedAndMovedCasesAndIdenticalCasesGetASuffixInFileOrder() {
    List<JqTestCase> before = parse(".\n1\n1\n\n.a\n{}\nnull\n");
    List<JqTestCase> after = parse(".a\n{}\nnull\n\n.b\n{}\nnull\n\n.\n1\n1\n\n.\n1\n1\n");

    assertThat(after.get(0).id()).isEqualTo(before.get(1).id());
    assertThat(after.get(2).id()).isEqualTo(before.get(0).id());
    assertThat(after.get(3).id()).isEqualTo(before.get(0).id() + "~2");
  }

  @Test
  void aChangedCaseGetsANewId() {
    assertThat(parse(".\n1\n1\n").get(0).id()).isNotEqualTo(parse(".\n1\n1\n1\n").get(0).id());
  }
}
