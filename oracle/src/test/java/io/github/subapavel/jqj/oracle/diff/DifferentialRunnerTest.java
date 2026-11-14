package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.cli.Invocation;
import io.github.subapavel.jqj.cli.Main;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The differential runner over the command line's in-process entry (ADR-0020, ADR-0024), on the cases recorded from
 * jq-ref in {@code diff/basics.rec}. Entries other than the stub shell stand in for a built command line.
 */
class DifferentialRunnerTest {

  @TempDir Path scratch;

  /** Loaded per test, not in a static initializer: PIT never re-runs one under a mutant. */
  private static DiffCorpus.Entry basics(String id) {
    return DiffCorpus.load(Corpus.casesDir()).stream().filter(e -> e.c().id().equals(id)).findFirst().orElseThrow();
  }

  private Verdict run(String id, ToIntFunction<Invocation> entry) {
    DiffCorpus.Entry e = basics(id);
    return runner(entry).run(e.c(), e.recording());
  }

  /** The runner over {@code entry} with the deviations of the repository's {@code DEVIATIONS.md}. */
  private DiffRunner runner(ToIntFunction<Invocation> entry) {
    return new DiffRunner(entry, scratch, Corpus.deviations());
  }

  @Test
  void theCorpusHoldsEveryCaseSortedByIdEachWithItsRecording() {
    List<DiffCorpus.Entry> all = DiffCorpus.load(Corpus.casesDir());

    assertThat(all)
        .extracting(e -> e.c().id())
        .filteredOn(id -> id.startsWith("basics/"))
        .containsExactly(
            "basics/compact-object", "basics/field-of-array", "basics/file-argument", "basics/index-of-array", "basics/recurse-of-array", "basics/syntax-error-eof");
    assertThat(all).extracting(e -> e.c().id()).isSorted().anyMatch(id -> id.startsWith("gen/values-v1/"));
    assertThat(all).allSatisfy(e -> assertThat(e.recording().id()).isEqualTo(e.c().id()));
  }

  /** An entry standing in for jqj: writes the given bytes and returns rc. */
  private static ToIntFunction<Invocation> jqj(int rc, String stdout, String stderr) {
    return inv -> {
      try {
        inv.out().write(stdout.getBytes(UTF_8));
        inv.err().write(stderr.getBytes(UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return rc;
    };
  }

  /**
   * A recorded case whose jq-ref rc differs from jqj's Stub rc. #33 built {@code -c .} and #34 {@code .a} (#33 design
   * C12, #34 design C4), and #63 builds {@code .[0]} ({@code basics/index-of-array}, which served here until then), so
   * this runs {@code basics/recurse-of-array}: {@code -c ..} on {@code [1]}, which jq-ref answers with rc 0 and
   * {@code [1]} then {@code 1} (basics.rec), and which stays a compile Stub (rc 3) after #63.
   */
  @Test
  void aRecordedCaseFailsAgainstTheStubShellWithAReadableDiff() {
    Verdict v = run("basics/recurse-of-array", Main::run);

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.diff())
        .contains("basics/recurse-of-array")
        .contains("rc      jq-ref 0\n          jqj    3\n")
        .contains("stdout  jq-ref [1]\\n1\\n\n          jqj    (empty)\n")
        .contains("stderr  jq-ref (empty)\n")
        .contains("        jqj    jqj stub: ");
    assertThat(v.detail()).doesNotContain("\n").doesNotContain("\t");
  }

  /**
   * A recorded syntax-error case against a shell that ends in a Stub, and against the real command line. Until #62
   * {@code Main::run} was that shell: on {@code basics/syntax-error-eof} ({@code .a |}) it gave rc 3, no stdout and
   * the Stub on stderr (observed on 00e3e3d, #62's red), so rc and stdout agree with jq-ref and only the Stub rule fails
   * it. #62 built the syntax error, so the stand-ins below give those bytes, on stderr and as an exception out of the
   * entry, and the real command line now passes.
   */
  @Test
  void aRecordedSyntaxErrorCaseIsFailAgainstAStubShellAndPassesOnTheCommandLine() {
    String stub = "jqj stub: the filter's END token at byte 4 is not built yet";
    Verdict onStderr = run("basics/syntax-error-eof", jqj(3, "", stub + "\n"));
    assertThat(onStderr.kind()).as("verdict of a shell whose stderr is the Stub").isEqualTo(Verdict.Kind.FAIL);
    assertThat(onStderr.diff()).as("diff of a shell whose stderr is the Stub").contains("jqj ended with a Stub").contains("rc      same").contains("stdout  same");

    Verdict thrown =
        run(
            "basics/syntax-error-eof",
            inv -> {
              throw new JqCompileException(stub, 1, 1);
            });
    assertThat(thrown.kind()).as("verdict of a shell that throws the Stub").isEqualTo(Verdict.Kind.FAIL);
    assertThat(thrown.detail()).as("detail of a shell that throws the Stub").startsWith("jqj ended with a Stub");

    Verdict real = run("basics/syntax-error-eof", Main::run);
    assertThat(real.kind()).as("verdict of the command line since #62").isEqualTo(Verdict.Kind.PASS);
    assertThat(real.detail()).as("detail of the command line since #62").isEqualTo("stderr not compared: syntax error");
  }

  @Test
  void aStubIsFailEvenWhereTheSyntaxErrorRuleComparesOnlyRcAndStdout() {
    Verdict v = run("basics/syntax-error-eof", jqj(3, "", "jqj stub: the parser is not built yet\n"));

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).contains("Stub");
  }

  @Test
  void aSyntaxErrorCaseComparesRcAndStdoutOnly() {
    Verdict pass = run("basics/syntax-error-eof", jqj(3, "", "jq: error: jqj's own syntax error text\n"));
    assertThat(pass.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(pass.detail()).contains("stderr not compared");
    assertThat(run("basics/syntax-error-eof", jqj(2, "", "")).kind()).isEqualTo(Verdict.Kind.FAIL);
  }

  @Test
  void aCaseWhoseOutputMatchesTheRecordingByteForBytePasses() {
    ToIntFunction<Invocation> cat =
        inv -> {
          try {
            inv.in().transferTo(inv.out());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return 0;
        };
    // -c . on compact input prints the input unchanged, so an entry that copies stdin to stdout agrees with jq-ref.
    Verdict v = run("basics/compact-object", cat);

    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(v.detail()).isEmpty();
  }

  @Test
  void stderrIsComparedByteForByteOutsideSyntaxErrors() {
    String recorded = "jq: error (at <stdin>:0): Cannot index array with string (\"a\")\n";
    assertThat(run("basics/field-of-array", jqj(5, "", recorded)).kind()).isEqualTo(Verdict.Kind.PASS);

    Verdict v = run("basics/field-of-array", jqj(5, "", "jq: error (at <stdin>:1): Cannot index array with string (\"a\")\n"));
    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.diff()).contains("stderr  jq-ref " + CaseFormat.escape(recorded.getBytes(UTF_8))).contains("rc      same");

    Verdict fromTheFirstByte = run("basics/field-of-array", jqj(5, "", "other\n"));
    assertThat(fromTheFirstByte.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(fromTheFirstByte.detail()).isEqualTo("stderr differs at byte 0");
  }

  @Test
  void theEntryGetsTheCaseEnvironmentOnTopOfHomeTzAndLcAll() {
    String text = "case t/env\narg -n\nfilter $ENV.K\nenv K=v\nenv TZ=Asia/Tokyo\nend\n";
    DiffCase c = DiffCase.of("t.cases", CaseFormat.parse("t.cases", "t", text.getBytes(UTF_8)).get(0));
    Map<String, String> seenEnv = new HashMap<>();

    runner(
            inv -> {
              seenEnv.putAll(inv.env());
              return 0;
            })
        .run(c, null);

    assertThat(seenEnv).containsEntry("K", "v").containsEntry("TZ", "Asia/Tokyo").containsEntry("LC_ALL", "C").containsKey("HOME");
  }

  @Test
  void theEntryGetsTheCaseFilesInItsWorkingDirectoryAndExactlyTheCaseEnvironment() {
    Map<String, String> seenEnv = new HashMap<>();
    Path[] seenCwd = new Path[1];
    ToIntFunction<Invocation> probe =
        inv -> {
          seenEnv.putAll(inv.env());
          seenCwd[0] = inv.cwd();
          try {
            inv.out().write(Files.readAllBytes(inv.cwd().resolve("in.json")));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return 0;
        };

    Verdict v = run("basics/file-argument", probe);

    // jq-ref printed the file compactly; the file is already compact, so copying it agrees.
    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(seenEnv).containsOnly(Map.entry("HOME", seenCwd[0].toString()), Map.entry("TZ", "UTC"), Map.entry("LC_ALL", "C"));
  }

  @Test
  void aCaseChangedAfterItsRecordingIsStale() {
    DiffCorpus.Entry e = basics("basics/compact-object");
    DiffCase edited =
        new DiffCase(e.c().id(), e.c().args(), "{\"b\":1}\n".getBytes(UTF_8), e.c().files(), e.c().env(), null, DiffCase.JqjColumn.NONE, "000000000000");

    Verdict v = runner(jqj(0, "{\"a\":1}\n", "")).run(edited, e.recording());

    assertThat(v.kind()).isEqualTo(Verdict.Kind.STALE);
    assertThat(v.detail()).contains("000000000000").contains(e.recording().def());
  }

  @Test
  void aCaseWithoutARecordingIsStale() {
    DiffCorpus.Entry e = basics("basics/compact-object");

    assertThat(runner(jqj(0, "{\"a\":1}\n", "")).run(e.c(), null).kind()).isEqualTo(Verdict.Kind.STALE);
  }

  @Test
  void aStubWithoutARecordingIsFailNotStale() {
    // A test-local case with no recording: --arg is still a Stub of the command line (#38). basics/field-of-array
    // served here until #37 built the pretty output it needs.
    DiffCase stub =
        new DiffCase(
            "test/arg-stub", List.of("--arg", "x", "1", "."), "{\"a\":1}\n".getBytes(UTF_8), Map.of(), Map.of(), null, DiffCase.JqjColumn.NONE, "000000000000");

    Verdict v = runner(Main::run).run(stub, null);

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.diff()).contains("no recording");
  }

  @Test
  void theDiffOfALongOrLateDifferenceShowsAWindowAroundIt() {
    Verdict longOutput = run("basics/compact-object", jqj(0, "x".repeat(1000), ""));
    assertThat(longOutput.diff()).contains("jqj    " + "x".repeat(200) + "... (1000 bytes)");

    Verdict late = run("basics/field-of-array", jqj(5, "", "jq: error (at <stdin>:0): Cannot index array with string (\"b\")\n"));
    assertThat(late.diff()).contains("first difference at byte 59").contains("jq-ref ...").contains("jqj    ...");
  }

  @Test
  void anExceptionOutOfTheEntryIsACrashUnlessItIsAStub() {
    Verdict crash =
        run(
            "basics/compact-object",
            inv -> {
              throw new IllegalStateException("boom");
            });
    assertThat(crash.kind()).isEqualTo(Verdict.Kind.CRASH);
    assertThat(crash.detail()).contains("IllegalStateException").contains("boom");

    Verdict stub =
        run(
            "basics/compact-object",
            inv -> {
              throw new IllegalStateException("jqj stub: the command line is not built yet");
            });
    assertThat(stub.kind()).isEqualTo(Verdict.Kind.FAIL);
  }

  /** A test-local deviation case: {@code -c .} with the given {@code deviation} and {@code jqj-*} lines. */
  private static DiffCase deviationCase(String lines) {
    String text = "case t/deviation\narg -c\nfilter .\n" + lines + "end\n";
    return DiffCase.of("t.cases", CaseFormat.parse("t.cases", "t", text.getBytes(UTF_8)).get(0));
  }

  /** A recording of {@code c} as jq-ref would give it on {@code 123\x00}: rc 0, {@code 123}, empty stderr. */
  private static Recording acceptedByJqRef(DiffCase c) {
    return new Recording(c.id(), c.def(), new Outcome(0, "123\n".getBytes(UTF_8), new byte[0]));
  }

  @Test
  void aDeviationCaseIsComparedWithItsJqjKeysAndWithTheRecordingWhereAKeyIsAbsent() {
    DiffCase c = deviationCase("deviation nul\njqj-rc 5\njqj-stdout\n");

    Verdict jqjsColumn = runner(jqj(5, "", "")).run(c, acceptedByJqRef(c));
    assertThat(jqjsColumn.kind()).isEqualTo(Verdict.Kind.PASS);

    Verdict jqRefsColumn = runner(jqj(0, "123\n", "")).run(c, acceptedByJqRef(c));
    assertThat(jqRefsColumn.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(jqRefsColumn.detail()).isEqualTo("rc 0, expect 5; stdout differs at byte 0");
    assertThat(jqRefsColumn.diff())
        .contains("t/deviation: FAIL against deviation nul's jqj-* keys, the recording where one is absent\n")
        .contains("rc      expect 5\n          jqj    0\n")
        .contains("stdout  expect (empty)\n          jqj    123\\n\n");

    // No jqj-stderr: the recorded stderr, empty, is what jqj must print.
    Verdict stderr = runner(jqj(5, "", "jq: error\n")).run(c, acceptedByJqRef(c));
    assertThat(stderr.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(stderr.detail()).isEqualTo("stderr differs at byte 0");
  }

  /** ADR-0020: a recording equal to the case's jqj column fails as "deviation gone", whatever jqj gives. */
  @Test
  void aDeviationCaseWhoseRecordingEqualsItsJqjKeysFailsAsDeviationGone() {
    DiffCase c = deviationCase("deviation nul\njqj-rc 0\njqj-stdout 123\\n\n");

    Verdict v = runner(jqj(0, "123\n", "")).run(c, acceptedByJqRef(c));

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).isEqualTo("deviation gone");
    assertThat(v.diff()).startsWith("t/deviation: FAIL, deviation gone: jq-ref's recording equals the jqj-* keys of deviation nul");
  }

  @Test
  void aCaseWithoutDeviationIsComparedWithTheRecordingUnderJqRefsName() {
    Verdict v = run("basics/compact-object", jqj(1, "", ""));

    assertThat(v.detail()).startsWith("rc 1, jq-ref 0;");
    assertThat(v.diff()).startsWith("basics/compact-object: FAIL\n").contains("rc      jq-ref 0\n");
  }

  @Test
  void aDeviationSlugThatNamesNoRowOfDeviationsMdIsAnErrorBeforeJqjRuns() {
    DiffCase c = deviationCase("deviation no-such-row\njqj-rc 5\n");
    boolean[] ran = new boolean[1];
    DiffRunner r =
        runner(
            inv -> {
              ran[0] = true;
              return 5;
            });

    assertThatThrownBy(() -> r.run(c, acceptedByJqRef(c)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("case t/deviation: deviation 'no-such-row' names no row of DEVIATIONS.md");
    assertThat(ran[0]).isFalse();
  }

  @Test
  void noWorkingDirectoryIsLeftBehind() throws IOException {
    List<Path> parents = new ArrayList<>();
    run(
        "basics/file-argument",
        inv -> {
          parents.add(inv.cwd().getParent());
          return Main.run(inv);
        });
    run(
        "basics/file-argument",
        inv -> {
          parents.add(inv.cwd().getParent());
          throw new IllegalStateException("boom");
        });

    // The witness: both runs had their working directory in scratch, so an empty scratch means both were deleted.
    assertThat(parents).containsExactly(scratch, scratch);
    try (Stream<Path> left = Files.list(scratch)) {
      assertThat(left).isEmpty();
    }
  }
}
