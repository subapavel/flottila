package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The jqtest oracle report, in the format scripts/ratchet.java reads: id, verdict, detail (ADR-0020). */
class JqTestReportTest {

  @TempDir Path dir;

  @Test
  void theReportHoldsIdVerdictAndDetailOneLinePerCaseSortedById() throws IOException {
    Path report = dir.resolve("reports/jqtest-interpreter.tsv");

    JqTestReport.merge(
        report,
        List.of(
            new JqTestReport.Row("jq.test#bbbbbbbbbbbb", new Verdict(Verdict.Kind.FAIL, "jqj ended with a Stub: x", "diff")),
            new JqTestReport.Row("base64.test#aaaaaaaaaaaa", new Verdict(Verdict.Kind.PASS, "", ""))));

    assertThat(Files.readString(report, UTF_8)).isEqualTo("base64.test#aaaaaaaaaaaa\tPASS\t\njq.test#bbbbbbbbbbbb\tFAIL\tjqj ended with a Stub: x\n");
  }

  @Test
  void aMergeReplacesTheRowsOfItsCasesAndKeepsTheOthers() throws IOException {
    Path report = dir.resolve("jqtest-interpreter.tsv");
    Files.writeString(report, "a#1\tFAIL\tx\nc#3\tPASS\t\n", UTF_8);

    JqTestReport.merge(report, List.of(new JqTestReport.Row("a#1", new Verdict(Verdict.Kind.PASS, "", ""))));

    assertThat(Files.readString(report, UTF_8)).isEqualTo("a#1\tPASS\t\nc#3\tPASS\t\n");
  }

  @Test
  void aReportThatCannotBeWrittenFailsInsteadOfDroppingTheVerdicts() throws IOException {
    Path file = Files.writeString(dir.resolve("a-file"), "", UTF_8);

    assertThatThrownBy(() -> JqTestReport.merge(file.resolve("jqtest-interpreter.tsv"), List.of()))
        .isInstanceOf(UncheckedIOException.class);
  }
}
