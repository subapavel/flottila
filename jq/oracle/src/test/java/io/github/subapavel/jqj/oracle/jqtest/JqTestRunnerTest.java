package io.github.subapavel.jqj.oracle.jqtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.diff.Verdict;
import io.github.subapavel.jqj.oracle.jqtest.JqjException.Kind;
import io.github.subapavel.jqj.oracle.jqtest.JvValue.Num;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The verdict rules of jq_test.c and ADR-0020/ADR-0024, against a jqj that is a script: each test sets what
 * compiling, reading and running do.
 */
class JqTestRunnerTest {

  private static Num n(int i) {
    return new Num(i, BigDecimal.valueOf(i), true);
  }

  private static JqjException stub(String what) {
    return new JqjException(Kind.COMPILE, "jqj stub: " + what + " is not built yet", List.of("jqj stub: " + what + " is not built yet"));
  }

  private static JqjException compileError(String... blocks) {
    return new JqjException(Kind.COMPILE, String.join("\n", blocks), List.of(blocks));
  }

  /** A scripted jqj: numbers read as themselves; the run emits {@link #results}, then throws {@link #runError}. */
  private static final class Script implements Jqj {
    RuntimeException compileError;
    RuntimeException readError;
    JvValue reread;
    List<JvValue> results = List.of();
    RuntimeException runError;
    String input;
    int emitted;

    @Override
    public Filter compile(String program) {
      if (compileError != null) throw compileError;
      return this::run;
    }

    private void run(String in, Predicate<JvValue> sink) {
      input = in;
      for (JvValue r : results) {
        emitted++;
        if (!sink.test(r)) return;
      }
      if (runError != null) throw runError;
    }

    @Override
    public JvValue read(String json) {
      if (readError != null) throw readError;
      return n(Integer.parseInt(json));
    }

    @Override
    public JvValue reread(String json) {
      return reread != null ? reread : read(json);
    }
  }

  private final Script jqj = new Script();

  private Verdict run(JqTestCase.Mode mode, String program, String input, String... expected) {
    return new JqTestRunner(jqj).run(new JqTestCase("t.test#000000000000", 1, mode, program, input, List.of(expected)));
  }

  private Verdict results(String... expected) {
    return run(JqTestCase.Mode.RESULTS, ".[]", "[1,2]", expected);
  }

  private Verdict fails(String... expected) {
    return run(JqTestCase.Mode.FAIL, "{", null, expected);
  }

  @Test
  void theResultsEqualToTheExpectedLinesPass() {
    jqj.results = List.of(n(1), n(2));

    Verdict v = results("1", "2");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(v.diff()).isEmpty();
    assertThat(jqj.input).isEqualTo("[1,2]");
  }

  @Test
  void aMismatchFailsAndNamesTheFirstDifferingResult() {
    jqj.results = List.of(n(1), n(3), n(4));

    Verdict v = results("1", "2", "3");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).isEqualTo("result 2 is 3, expected 2");
    assertThat(v.diff()).contains("t.test#000000000000").contains("program .[]").contains("input   [1,2]");
  }

  @Test
  void aMismatchAtTheFirstResultIsTheOneNamed() {
    jqj.results = List.of(n(3), n(4));

    assertThat(results("1", "2").detail()).isEqualTo("result 1 is 3, expected 1");
  }

  @Test
  void tooFewResultsFail() {
    jqj.results = List.of(n(1));

    assertThat(results("1", "2").detail()).isEqualTo("insufficient results: 1 of 2");
  }

  @Test
  void anErrorBeforeTheLastExpectedResultFails() {
    jqj.results = List.of(n(1));
    jqj.runError = new JqjException(Kind.FILTER, "\"boom\"", List.of());

    Verdict v = results("1", "2");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).isEqualTo("insufficient results: 1 of 2, then FILTER \"boom\"");
  }

  @Test
  void anErrorAfterTheLastExpectedResultPasses() {
    jqj.results = List.of(n(1), n(2));
    jqj.runError = new JqjException(Kind.FILTER, "\"boom\"", List.of());

    assertThat(results("1", "2").kind()).isEqualTo(Verdict.Kind.PASS);
  }

  @Test
  void aSuperfluousResultFailsAndStopsTheRun() {
    jqj.results = List.of(n(1), n(2), n(3), n(4));

    Verdict v = results("1", "2");

    assertThat(v.detail()).isEqualTo("a superfluous result 3");
    assertThat(jqj.emitted).isEqualTo(3);
  }

  @Test
  void anInvalidInputFailsEvenWithNoExpectedResult() {
    jqj.runError = new JqjException(Kind.SYNTAX, "Unfinished JSON term at EOF at line 2, column 0", List.of());

    Verdict v = run(JqTestCase.Mode.RESULTS, "empty", "[");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).isEqualTo("the input is invalid: Unfinished JSON term at EOF at line 2, column 0");
  }

  @Test
  void aProgramThatDoesNotCompileFails() {
    jqj.compileError = compileError("jq: error: x\n    .a\n    ^", "jq: 1 compile error");

    Verdict v = results("1");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).isEqualTo("the program does not compile: jq: error: x\\n    .a\\n    ^\\njq: 1 compile error");
  }

  @Test
  void anExpectedLineJqjsReaderRejectsFails() {
    jqj.readError = new JqjException(Kind.SYNTAX, "Invalid literal", List.of());

    assertThat(results("1").detail()).isEqualTo("expected result 1 is invalid: Invalid literal");
  }

  @Test
  void anExpectedValueMustSurviveJqjsWriterAndReader() {
    jqj.results = List.of(n(1));
    jqj.reread = n(7);

    assertThat(results("1").detail()).isEqualTo("expected result 1 is 7 after jqj's writer and reader");
  }

  @Test
  void aStubIsFailBeforeEveryOtherRule() {
    // Each of these would PASS by its own rule without the Stub-first rule (ADR-0024, F-20).
    jqj.compileError = stub("JqEngine.compile");
    assertThat(run(JqTestCase.Mode.FAIL_IGNORE_MSG, "}", null).kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(fails("jq: error: syntax error, unexpected end of file").detail()).isEqualTo("jqj ended with a Stub: jqj stub: JqEngine.compile is not built yet");

    jqj.compileError = null;
    jqj.results = List.of(n(1));
    jqj.runError = stub("JqFilter.run");
    assertThat(results("1").detail()).isEqualTo("jqj ended with a Stub: jqj stub: JqFilter.run is not built yet");
  }

  @Test
  void aStubWhereACaseFailsAnywayIsReportedAsAStub() {
    jqj.readError = stub("Workspace.read");
    assertThat(results("1").detail()).startsWith("jqj ended with a Stub");

    jqj.readError = null;
    jqj.compileError = stub("JqEngine.compile");
    assertThat(results("1").detail()).startsWith("jqj ended with a Stub");
    assertThat(fails("jq: error: x").detail()).startsWith("jqj ended with a Stub");
  }

  @Test
  void anExceptionThatIsNotJqjsIsACrash() {
    jqj.runError = new IllegalStateException("jqj stub: a Stub prefix does not make it jqj's");

    Verdict v = results();

    assertThat(v.kind()).isEqualTo(Verdict.Kind.CRASH);
    assertThat(v.detail()).isEqualTo("java.lang.IllegalStateException: jqj stub: a Stub prefix does not make it jqj's");
  }

  @Test
  void aFailCaseThatCompilesFails() {
    Verdict v = fails("jq: error: x");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).isEqualTo("compiled, but the case expects a compile error");
  }

  @Test
  void aFailCaseComparesItsLinesWithTheLastErrorBlockByPrefixSkippingOneLfEachTime() {
    jqj.compileError = compileError("jq: error: first block", "jq: error: last at <top-level>, line 1:\n{\n^");

    assertThat(fails("jq: error: last at <top-level>, line 1:", "{", "^").kind()).isEqualTo(Verdict.Kind.PASS);
    // Each line is a prefix of what is left, so a line may stop short of its LF.
    assertThat(fails("jq: error: last", " at <top-level>, line 1:", "{", "^").kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(fails("jq: error: first block").detail()).isEqualTo("the compile error is 'jq: error: last at <top-level>, line 1:', expected 'jq: error: first block'");
  }

  @Test
  void aFailCaseWhoseErrorHasTextLeftOverFails() {
    jqj.compileError = compileError("jq: error: x\n    .a\n    ^");

    assertThat(fails("jq: error: x", "    .a").detail()).isEqualTo("the compile error has more text: '    ^'");
    // One LF is skipped after a line, not two: the leftover is an empty line, then more.
    jqj.compileError = compileError("jq: error: x\n\nmore");
    assertThat(fails("jq: error: x").detail()).isEqualTo("the compile error has more text: ''");
  }

  @Test
  void aFailCaseExpectingABisonSyntaxErrorChecksOnlyThatCompilingFails() {
    jqj.compileError = compileError("jq: error: jqj's own text\n.\n^");

    Verdict v = fails("jq: error: syntax error, unexpected '}' at <top-level>, line 1, column 7:", "    . as {} | null");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(v.detail()).isEqualTo("message not compared: syntax error");
  }

  @Test
  void aFailIgnoreMsgCaseChecksOnlyThatCompilingFails() {
    jqj.compileError = compileError("jq: error: anything");

    Verdict v = run(JqTestCase.Mode.FAIL_IGNORE_MSG, "}", null, "jq: error: something else");

    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(v.detail()).isEqualTo("message not compared: %%FAIL IGNORE MSG");
  }

  @Test
  void theSixFailCasesOfJqTestThatCheckOnlyThatCompilingFailsAreFailAgainstAStubCompiler() {
    List<JqTestCase> onlyCompile =
        JqTestCorpus.cases().stream()
            .filter(c -> c.id().startsWith("jq.test#") && c.mode() != JqTestCase.Mode.RESULTS)
            .filter(c -> c.mode() == JqTestCase.Mode.FAIL_IGNORE_MSG || c.expected().getFirst().startsWith("jq: error: syntax error"))
            .sorted(java.util.Comparator.comparingInt(JqTestCase::line))
            .toList();
    assertThat(onlyCompile).extracting(JqTestCase::line).containsExactly(547, 553, 1981, 1987, 2074, 2080);

    // Any compile error passes them: that is why the Stub must be FAIL first.
    jqj.compileError = compileError("jq: error: jqj's own syntax error text");
    assertThat(onlyCompile).allSatisfy(c -> assertThat(new JqTestRunner(jqj).run(c).kind()).isEqualTo(Verdict.Kind.PASS));

    jqj.compileError = stub("JqEngine.compile");
    assertThat(onlyCompile).allSatisfy(c -> assertThat(new JqTestRunner(jqj).run(c).kind()).isEqualTo(Verdict.Kind.FAIL));
  }

  @Test
  void aFailCaseWithNoLinesFailsOnAnyErrorText() {
    jqj.compileError = compileError("jq: error: x");

    assertThat(fails().kind()).isEqualTo(Verdict.Kind.FAIL);
  }

  @Test
  void theDetailIsOneLineWithoutTabs() {
    jqj.compileError = compileError("a\tb\nc");
    List<String> details = new ArrayList<>(List.of(results("1").detail(), fails("x").detail()));

    assertThat(details).allSatisfy(d -> assertThat(d).doesNotContain("\n").doesNotContain("\t"));
  }
}
