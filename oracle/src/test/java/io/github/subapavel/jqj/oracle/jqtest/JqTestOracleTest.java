package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.StackProbe;
import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The jqtest oracle (ADR-0020): one dynamic test per required case of jq's test files, sorted by id, which must PASS
 * through the public API. Every verdict goes into the jqtest report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JqTestOracleTest {

  private final List<JqTestReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> requiredCases() {
    return requiredCases(System.getProperty("oracle.require"));
  }

  /**
   * The tests of the ratcheted ids and the jqtest ids of {@code requireFile} (null: none); throws, listing them, if any
   * of those ids names no case.
   */
  Stream<DynamicTest> requiredCases(String requireFile) {
    Set<String> required = JqTestCorpus.required(requireFile);
    List<JqTestCase> cases = JqTestCorpus.cases();
    DiffReport.requireKnown(DiffReport.Oracle.JQTEST, required, cases.stream().map(JqTestCase::id).toList());
    JqTestRunner runner = JqTestCorpus.runner();
    return cases.stream()
        .filter(c -> required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      Verdict v = runner.run(c);
                      rows.add(new JqTestReport.Row(c.id(), v));
                      if (v.kind() != Verdict.Kind.PASS) fail(v.diff());
                    }));
  }

  @Test
  void aRequiredIdThatNamesNoCaseFailsTheRunAndTheMessageListsEveryUnknownId(@TempDir Path dir) throws IOException {
    Path require = dir.resolve("require.txt");
    Files.writeString(
        require,
        "# a comment\n\njq.test#1ce903e38afb\nman.test#ffffffffffff\njq.test#000000000000\njson/y_no_such_file\ndot/no-such-case\n",
        UTF_8);

    assertThatThrownBy(() -> requiredCases(require.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith(": [jq.test#000000000000, man.test#ffffffffffff]");
  }

  /** F-88: {@code max(4 × Limits.cli().stackBytes(), 2 MiB)}, with the command line's 512 KiB: 2 MiB. */
  private static final long RUNNER_STACK = 2L << 20;

  /**
   * F-88, #111: the runner the oracle tests build compiles and runs every case on one fixed thread, not the caller's,
   * and that thread holds the stack of a fresh {@value #RUNNER_STACK}-byte thread.
   */
  @Test
  void everyCaseRunsOnTheRunnersOneThread() {
    List<Thread> threads = new CopyOnWriteArrayList<>();
    List<Integer> frames = new CopyOnWriteArrayList<>();
    Runnable probe =
        () -> {
          threads.add(Thread.currentThread());
          frames.add(StackProbe.frames());
        };
    Jqj jqj =
        new Jqj() {
          @Override
          public Filter compile(String program) {
            probe.run();
            return (input, sink) -> probe.run();
          }

          @Override
          public JvValue read(String json) {
            throw new AssertionError("a case without expected results reads none");
          }

          @Override
          public JvValue reread(String json) {
            throw new AssertionError("a case without expected results reads none");
          }
        };
    JqTestRunner runner = JqTestCorpus.runner(jqj);
    JqTestCase c = new JqTestCase("t.test#000000000000", 1, JqTestCase.Mode.RESULTS, "empty", "null", List.of());

    assertThat(runner.run(c).kind()).as("the first case's verdict").isEqualTo(Verdict.Kind.PASS);
    assertThat(runner.run(c).kind()).as("the second case's verdict").isEqualTo(Verdict.Kind.PASS);

    assertThat(threads).as("the threads of each compile and run, two per case").hasSize(4);
    assertThat(threads.getFirst()).as("the thread of the first compile").isNotSameAs(Thread.currentThread());
    assertThat(threads).as("the threads of every compile and run").allSatisfy(t -> assertThat(t).isSameAs(threads.getFirst()));
    StackProbe.assertHolds(
        () -> {
          runner.run(c);
          return frames.getLast();
        },
        RUNNER_STACK,
        "the jqtest runner's run");
  }

  /** PIT reruns this factory for each of its dynamic tests; the corpus it takes is read once per JVM (F-84 (C)). */
  @Test
  void theCorpusIsReadOncePerJvm() {
    assertThat(JqTestCorpus.cases()).isSameAs(JqTestCorpus.cases());
  }

  @AfterAll
  void writeReport() {
    JqTestCorpus.report(rows);
  }
}
