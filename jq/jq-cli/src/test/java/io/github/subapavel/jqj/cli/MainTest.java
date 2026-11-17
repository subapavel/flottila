package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.Limits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-process entry (ADR-0020, ADR-0024). #33 built {@code -c .}, so its entry ended with its Stub (#33 design C12)
 * and {@link #compactDotPrintsTheDocumentAndExitsZero} checks it. #34 built {@code .a} and jq's error line, which
 * {@link #filterErrorPrintsJqsLineAndExitsFive} and {@link #fileArgumentNamesTheFileInTheErrorLine} check. #37 built
 * pretty output, so no argument ({@code .}) and {@code .a} without {@code -c} left the Stub set (#33 design C12) for
 * {@link #prettyCommandLinesPrintJqsRecordedOutputAndExitZero}. #38 built jq's text for an unknown option, which
 * {@link #unknownOptionPrintsJqsUsageAndExitsTwo} checks.
 */
class MainTest {

  /** jq-ref 1.8.2, recorded in diff/pretty.rec under the case named in each set: rc 0, this stdout, stderr empty. */
  static Stream<Arguments> prettyCommandLines() {
    return Stream.of(
        // pretty/no-filter
        Arguments.argumentSet(
            "no argument", List.of(), "{\"a\":[1,{\"b\":null}]}\n", "{\n  \"a\": [\n    1,\n    {\n      \"b\": null\n    }\n  ]\n}\n"),
        // pretty/field
        Arguments.argumentSet(
            ".a", List.of(".a"), "{\"a\":{\"b\":[1,{}],\"c\":\"d\"},\"z\":0}\n", "{\n  \"b\": [\n    1,\n    {}\n  ],\n  \"c\": \"d\"\n}\n"),
        // pretty/empty-stdin (the carry from #33): the sink is made before the input is found empty.
        Arguments.argumentSet(". on empty stdin", List.of("."), "", ""));
  }

  @Test
  void unknownOptionPrintsJqsUsageAndExitsTwo() {
    // jq-ref 1.8.2, recorded in diff/cli.rec (cli/unknown-long, for --nosuch) and observed 2026-11-12:
    //   printf '{"a":1}\n' | jq-ref --badopt -> rc 2, stdout empty, stderr these three lines.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation =
        new Invocation(List.of("--badopt"), Map.of(), Path.of("."), new ByteArrayInputStream("{\"a\":1}\n".getBytes(UTF_8)), out, err);

    int rc = Main.run(invocation);

    assertThat(rc).as("exit code").isEqualTo(2);
    assertThat(out.toByteArray()).as("stdout").isEmpty();
    assertThat(err.toString(UTF_8))
        .as("stderr")
        .isEqualTo(
            "jq: Unknown option --badopt\nUse jq --help for help with command-line options,\n"
                + "or see the jq manpage, or online docs at https://jqlang.org\n");
  }

  @Test
  void compactDotPrintsTheDocumentAndExitsZero() {
    // jq-ref 1.8.2, recorded in diff/basics.rec (basics/compact-object): printf '{"a":1}\n' | jq-ref -c .
    //   -> rc 0, stdout {"a":1} and LF, stderr empty.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation =
        new Invocation(List.of("-c", "."), Map.of(), Path.of("."), new ByteArrayInputStream("{\"a\":1}\n".getBytes(UTF_8)), out, err);

    int rc = Main.run(invocation);

    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo("{\"a\":1}\n");
    assertThat(err.toString(UTF_8)).as("stderr").isEmpty();
  }

  @Test
  void filterErrorPrintsJqsLineAndExitsFive() {
    // jq-ref 1.8.2, recorded in diff/field.rec (field/error-array, field/line-inner-lfs) and observed 2026-11-11:
    //   printf '[1]\n' | jq-ref -c .a           -> rc 5, stdout empty, jq: error (at <stdin>:1): Cannot index array with string ("a")
    //   printf '[\n1\n]' | jq-ref -c .a         -> rc 5, jq: error (at <stdin>:2): Cannot index array with string ("a")
    //   printf '{"a":[1]}\n' | jq-ref -c '.a | .b' -> rc 5, jq: error (at <stdin>:1): Cannot index array with string ("b")
    String[][] cases = {
      {".a", "[1]\n", "jq: error (at <stdin>:1): Cannot index array with string (\"a\")\n"},
      {".a", "[\n1\n]", "jq: error (at <stdin>:2): Cannot index array with string (\"a\")\n"},
      {".a | .b", "{\"a\":[1]}\n", "jq: error (at <stdin>:1): Cannot index array with string (\"b\")\n"},
    };
    for (String[] c : cases) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Invocation invocation = new Invocation(List.of("-c", c[0]), Map.of(), Path.of("."), new ByteArrayInputStream(c[1].getBytes(UTF_8)), out, err);

      int rc = Main.run(invocation);

      assertThat(err.toString(UTF_8)).as("stderr of -c %s", c[0]).isEqualTo(c[2]);
      assertThat(rc).as("exit code of -c %s", c[0]).isEqualTo(5);
      assertThat(out.toByteArray()).as("stdout of -c %s", c[0]).isEmpty();
    }
  }

  @Test
  void filterErrorOfAnyValuePrintsJqsLineAndExitsFive() {
    // jq-ref 1.8.2, recorded in diff/control.rec (#68, ADR-0039): a value that is no string after "(not a string): ",
    // a view payload included, and a string cut at its first NUL, as jq's %s prints it.
    //   control/error-array        error([1,"a",null]) on null    -> jq: error (at <stdin>:1) (not a string): [1,"a",null]
    //   control/error-view-payload .a | error on {"a":{"b":1}}    -> jq: error (at <stdin>:1) (not a string): {"b":1}
    //   control/error-nul-string   "a\\u0000b" | error on null   -> jq: error (at <stdin>:1): a
    String nul = "\"a" + "\\" + "u0000b\" | error";
    String[][] cases = {
      {"error([1,\"a\",null])", "null\n", "jq: error (at <stdin>:1) (not a string): [1,\"a\",null]\n"},
      {".a | error", "{\"a\":{\"b\":1}}\n", "jq: error (at <stdin>:1) (not a string): {\"b\":1}\n"},
      {nul, "null\n", "jq: error (at <stdin>:1): a\n"},
    };
    for (String[] c : cases) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Invocation invocation = new Invocation(List.of("-c", c[0]), Map.of(), Path.of("."), new ByteArrayInputStream(c[1].getBytes(UTF_8)), out, err);

      int rc = Main.run(invocation);

      assertThat(err.toString(UTF_8)).as("stderr of -c %s", c[0]).isEqualTo(c[2]);
      assertThat(rc).as("exit code of -c %s", c[0]).isEqualTo(5);
      assertThat(out.toByteArray()).as("stdout of -c %s", c[0]).isEmpty();
    }
  }

  @Test
  void fileArgumentNamesTheFileInTheErrorLine(@TempDir Path cwd) throws IOException {
    // jq-ref 1.8.2, recorded (field/error-file-argument) and observed 2026-11-11: the file argument as given.
    //   printf '[1]\n' > in.json; jq-ref -c .a in.json            -> jq: error (at in.json:1): Cannot index array with string ("a")
    //   jq-ref -c .a ./in.json                                    -> jq: error (at ./in.json:1): ...
    //   printf '\n\n5\n' > sub/doc.json; jq-ref -c .b sub/doc.json -> jq: error (at sub/doc.json:3): Cannot index number with string ("b")
    Files.write(cwd.resolve("in.json"), "[1]\n".getBytes(UTF_8));
    Files.createDirectories(cwd.resolve("sub"));
    Files.write(cwd.resolve("sub/doc.json"), "\n\n5\n".getBytes(UTF_8));
    String[][] cases = {
      {".a", "in.json", "jq: error (at in.json:1): Cannot index array with string (\"a\")\n"},
      {".a", "./in.json", "jq: error (at ./in.json:1): Cannot index array with string (\"a\")\n"},
      {".b", "sub/doc.json", "jq: error (at sub/doc.json:3): Cannot index number with string (\"b\")\n"},
    };
    for (String[] c : cases) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      // stdin holds another document: the error must name the file and count its lines.
      Invocation invocation = new Invocation(List.of("-c", c[0], c[1]), Map.of(), cwd, new ByteArrayInputStream("[9]\n".getBytes(UTF_8)), out, err);

      int rc = Main.run(invocation);

      assertThat(err.toString(UTF_8)).as("stderr of -c %s %s", c[0], c[1]).isEqualTo(c[2]);
      assertThat(rc).as("exit code of -c %s %s", c[0], c[1]).isEqualTo(5);
      assertThat(out.toByteArray()).as("stdout of -c %s %s", c[0], c[1]).isEmpty();
    }
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("prettyCommandLines")
  void prettyCommandLinesPrintJqsRecordedOutputAndExitZero(List<String> args, String stdin, String recordedStdout) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation = new Invocation(args, Map.of(), Path.of("."), new ByteArrayInputStream(stdin.getBytes(UTF_8)), out, err);

    int rc = Main.run(invocation);

    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo(recordedStdout);
    assertThat(err.toString(UTF_8)).as("stderr").isEmpty();
  }

  @Test
  void aFailingStderrIsReportedNotSwallowed(@TempDir Path cwd) {
    // A missing file still writes to stderr (#38, diff/cli.rec cli/missing-file); the `.` on empty stdin used before #37
    // writes nothing now.
    OutputStream broken =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("stderr closed");
          }
        };
    Invocation invocation =
        new Invocation(
            List.of(".", "missing.json"), Map.of(), cwd, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), broken);

    assertThatThrownBy(() -> Main.run(invocation)).isInstanceOf(UncheckedIOException.class).hasMessageContaining("stderr closed");
  }

  @Test
  void theBuilderHookShapesTheEngineOfTheRun() {
    // The compile-mode oracle's entry (ADR-0020 amendment for #60): the hook gets the command line's builder last, so
    // its limits replace Limits.cli(). With no steps, the read's one tick for "1" ends the run (StepBudgetTest).
    Limits cli = Limits.cli();
    Limits noSteps =
        new Limits(0, cli.deadline(), cli.nestingDepth(), cli.recursionDepth(), cli.bytesBuilt(), cli.bytesWritten(), cli.regexMatchTime(), cli.stackBytes());

    assertThatThrownBy(() -> Main.run(compactDotOnOne(new ByteArrayOutputStream()), b -> b.limits(noSteps)))
        .as("the run with a hook that sets no steps")
        .isInstanceOfSatisfying(JqTermination.class, t -> assertThat(t.budget()).as("budget").isEqualTo(JqTermination.Budget.STEPS));

    // Witness: the identity hook runs as run(Invocation). jq-ref 1.8.2, observed 2026-11-12: printf '1\n' | jq-ref -c .
    //   -> rc 0, stdout 1 and LF.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThat(Main.run(compactDotOnOne(out), UnaryOperator.identity())).as("exit code with the identity hook").isZero();
    assertThat(out.toString(UTF_8)).as("stdout with the identity hook").isEqualTo("1\n");
  }

  private static Invocation compactDotOnOne(OutputStream out) {
    return new Invocation(List.of("-c", "."), Map.of(), Path.of("."), new ByteArrayInputStream("1\n".getBytes(UTF_8)), out, new ByteArrayOutputStream());
  }
}
