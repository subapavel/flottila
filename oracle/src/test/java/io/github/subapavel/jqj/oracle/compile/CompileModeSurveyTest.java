package io.github.subapavel.jqj.oracle.compile;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Survey of the compile-mode oracle (ADR-0020 amendment for #60): every case nobody requires runs in both modes and
 * goes into the compile report; a test fails only on CRASH. A Disagreement fails no test here: {@code regen} refuses
 * it (F-29). It runs only under the Maven profile {@code compile-oracle}, and PIT excludes it (oracle/pom.xml).
 */
@Tag(CompileCorpus.TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompileModeSurveyTest {

  private final List<DiffReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> survey(@TempDir Path scratch) {
    Set<String> required = CompileCorpus.required(CompileCorpus.ratchet());
    return CompileCorpus.cases(scratch).stream()
        .filter(c -> !required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      Verdict v = CompileCorpus.judge(c);
                      rows.add(new DiffReport.Row(c.id(), v, c.def()));
                      if (v.kind() == Verdict.Kind.CRASH) fail(v.diff());
                    }));
  }

  @AfterAll
  void writeReport() {
    CompileCorpus.report(rows);
  }
}
