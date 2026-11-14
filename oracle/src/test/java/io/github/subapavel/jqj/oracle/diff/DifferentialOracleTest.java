package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.cli.Main;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The differential oracle (ADR-0020): one dynamic test per required case, sorted by id, which must PASS against
 * jq-ref's recording. Every verdict goes into the oracle report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DifferentialOracleTest {

  private final List<DiffReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> requiredCases(@TempDir Path scratch) {
    return requiredCases(scratch, System.getProperty("oracle.require"));
  }

  /**
   * The tests of the ratcheted ids and the differential ids of {@code requireFile} (null: none); throws, listing them,
   * if any of those ids names no case.
   */
  Stream<DynamicTest> requiredCases(Path scratch, String requireFile) {
    Set<String> required = Corpus.required(requireFile);
    List<DiffCorpus.Entry> cases = Corpus.cases();
    DiffReport.requireKnown(DiffReport.Oracle.DIFFERENTIAL, required, cases.stream().map(e -> e.c().id()).toList());
    DiffRunner runner = new DiffRunner(Main::run, scratch, Corpus.deviations());
    return cases.stream()
        .filter(e -> required.contains(e.c().id()))
        .map(
            e ->
                dynamicTest(
                    e.c().id(),
                    () -> {
                      Verdict v = runner.run(e.c(), e.recording());
                      rows.add(new DiffReport.Row(e.c().id(), v, e.c().def()));
                      if (v.kind() != Verdict.Kind.PASS) fail(v.diff());
                    }));
  }

  @Test
  void aRequiredIdThatNamesNoCaseFailsTheRunAndTheMessageListsEveryUnknownId(@TempDir Path dir) throws IOException {
    Path require = dir.resolve("require.txt");
    Files.writeString(
        require,
        "# a comment\n\ndot/null\ndot/no-such-case\njq.test:58\njson/y_no_such_file\njq.test#000000000000\n",
        UTF_8);

    assertThatThrownBy(() -> requiredCases(dir, require.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith(": [dot/no-such-case, jq.test:58]");
  }

  /** PIT reruns this factory for each of its dynamic tests; the corpus it takes is read once per JVM (F-84 (C)). */
  @Test
  void theCorpusIsReadOncePerJvm() {
    assertThat(Corpus.cases()).isSameAs(Corpus.cases());
  }

  @AfterAll
  void writeReport() {
    Corpus.report(rows);
  }
}
