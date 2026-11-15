package io.github.subapavel.jqj.oracle.diff;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.cli.Main;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Survey of the differential oracle (ADR-0020): every case nobody requires runs and goes into the oracle report;
 * a test fails only on CRASH. PIT excludes this class (oracle/pom.xml).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DifferentialSurveyTest {

  private final List<DiffReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> survey(@TempDir Path scratch) {
    Set<String> required = Corpus.required();
    DiffRunner runner = Corpus.runner(scratch, Main::run);
    return Corpus.cases().stream()
        .filter(e -> !required.contains(e.c().id()))
        .map(
            e ->
                dynamicTest(
                    e.c().id(),
                    () -> {
                      Verdict v = runner.run(e.c(), e.recording());
                      rows.add(new DiffReport.Row(e.c().id(), v, e.c().def()));
                      if (v.kind() == Verdict.Kind.CRASH) fail(v.diff());
                    }));
  }

  @AfterAll
  void writeReport() {
    Corpus.report(rows);
  }
}
