package io.github.subapavel.jqj.oracle.compile;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Survey of the compile-mode oracle (ADR-0020 amendment for #60, F-58): every case runs under each mode nobody
 * requires it in, against one optimised run, and goes into that mode's compile report; a test fails only on CRASH. A
 * Disagreement fails no test here: {@code regen} refuses it (F-29). It runs only under the Maven profile
 * {@code compile-oracle}, and PIT excludes it (oracle/pom.xml).
 */
@Tag(CompileCorpus.TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompileModeSurveyTest {

  private final Map<String, List<DiffReport.Row>> rows = new HashMap<>();

  @TestFactory
  Stream<DynamicTest> survey(@TempDir Path scratch) {
    Map<String, Set<String>> required = CompileCorpus.required(CompileCorpus.ratchet());
    return CompileCorpus.cases(scratch).stream()
        .filter(c -> !CompileCorpus.modes(c.id(), required, false).isEmpty())
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      StringBuilder crashed = new StringBuilder();
                      CompileCorpus.judge(c, CompileCorpus.modes(c.id(), required, false))
                          .forEach(
                              (mode, v) -> {
                                rows.computeIfAbsent(mode, m -> new ArrayList<>()).add(new DiffReport.Row(c.id(), v, c.def()));
                                if (v.kind() == Verdict.Kind.CRASH) crashed.append(v.diff());
                              });
                      if (!crashed.isEmpty()) fail(crashed.toString());
                    }));
  }

  @AfterAll
  void writeReport() {
    CompileCorpus.report(rows);
  }
}
