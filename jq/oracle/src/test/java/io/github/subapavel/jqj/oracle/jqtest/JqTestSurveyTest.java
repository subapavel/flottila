package io.github.subapavel.jqj.oracle.jqtest;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

/**
 * The Survey of the jqtest oracle (ADR-0020): every case of jq's test files nobody requires runs and goes into the
 * jqtest report; a test fails only on CRASH, an exception that is not jqj's. PIT excludes this class (oracle/pom.xml).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JqTestSurveyTest {

  private final List<JqTestReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> survey() {
    Set<String> required = JqTestCorpus.required();
    JqTestRunner runner = JqTestCorpus.runner();
    return JqTestCorpus.cases().stream()
        .filter(c -> !required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      Verdict v = runner.run(c);
                      rows.add(new JqTestReport.Row(c.id(), v));
                      if (v.kind() == Verdict.Kind.CRASH) fail(v.diff());
                    }));
  }

  @AfterAll
  void writeReport() {
    JqTestCorpus.report(rows);
  }
}
