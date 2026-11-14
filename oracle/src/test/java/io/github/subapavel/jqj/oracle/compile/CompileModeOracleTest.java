package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-mode oracle (ADR-0020 amendment for #60): one dynamic test per id of {@code ratchet/oracle/compile.txt},
 * sorted by id, which must PASS, its optimised and conservative runs agreeing. Every verdict goes into the compile
 * report. It runs only under the Maven profile {@code compile-oracle} (tag {@value CompileCorpus#TAG}), and PIT
 * excludes it (oracle/pom.xml).
 */
@Tag(CompileCorpus.TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompileModeOracleTest {

  private final List<DiffReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> requiredCases(@TempDir Path scratch) {
    return requiredCases(scratch, CompileCorpus.ratchet());
  }

  /** The tests of the ids of {@code ratchet}; throws, listing them, if any of those ids names no case. */
  Stream<DynamicTest> requiredCases(Path scratch, Path ratchet) {
    Set<String> required = CompileCorpus.required(ratchet);
    List<CompileCorpus.Case> cases = CompileCorpus.cases(scratch);
    CompileCorpus.requireKnown(required, cases);
    return cases.stream()
        .filter(c -> required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      Verdict v = CompileCorpus.judge(c);
                      rows.add(new DiffReport.Row(c.id(), v, c.def()));
                      if (v.kind() != Verdict.Kind.PASS) fail(v.diff());
                    }));
  }

  @Test
  void aRequiredIdThatNamesNoCaseFailsTheRunAndTheMessageListsEveryUnknownId(@TempDir Path dir) throws IOException {
    Path ratchet = dir.resolve("compile.txt");
    Files.writeString(
        ratchet,
        "# a comment\n\ndot/null\td\tconservative\ndot/no-such-case\td\tconservative\njq.test#000000000000\t000000000000\tconservative\n",
        UTF_8);

    assertThatThrownBy(() -> requiredCases(dir, ratchet))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith(": [dot/no-such-case, jq.test#000000000000]");
  }

  @AfterAll
  void writeReport() {
    CompileCorpus.report(rows);
  }
}
