package dev.rail8.jq.oracle.json;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import dev.rail8.jq.oracle.diff.DiffReport;
import dev.rail8.jq.oracle.diff.DiffRunner;
import dev.rail8.jq.oracle.diff.Verdict;
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
 * The Survey of the json oracle (ADR-0020): every JSONTestSuite case nobody requires runs and goes into the json
 * report; a test fails only on CRASH. PIT excludes this class (oracle/pom.xml).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonSurveyTest {

  private final List<DiffReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> survey(@TempDir Path scratch) {
    Set<String> required = JsonCorpus.required();
    DiffRunner runner = JsonCorpus.runner(scratch);
    return JsonCorpus.cases().stream()
        .filter(e -> !required.contains(e.c().id()))
        .map(
            e ->
                dynamicTest(
                    e.c().id(),
                    () -> {
                      Verdict v = runner.run(e.c(), e.recording());
                      rows.add(JsonCorpus.row(e, v));
                      if (v.kind() == Verdict.Kind.CRASH) fail(v.diff());
                    }));
  }

  @AfterAll
  void writeReport() {
    JsonCorpus.report(rows);
  }
}
