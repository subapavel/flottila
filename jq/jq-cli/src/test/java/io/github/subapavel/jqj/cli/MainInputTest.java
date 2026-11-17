package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where {@code -c .} takes its input from, by the #33 design's rc and stderr table: stdin or one file, blank input,
 * a parse error, and the input errors that stay Stubs. The oracle runs the same paths; this module's coverage counts
 * only its own tests.
 */
class MainInputTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @TempDir Path cwd;

  /** The outcome of one run: exit code, stdout and stderr. */
  private record Outcome(int rc, String out, String err) {}

  private Outcome run(List<String> args, InputStream stdin) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc = Main.run(new Invocation(args, Map.of(), cwd, stdin, out, err));
    return new Outcome(rc, out.toString(UTF_8), err.toString(UTF_8));
  }

  private Outcome run(List<String> args, String stdin) {
    return run(args, new ByteArrayInputStream(stdin.getBytes(UTF_8)));
  }

  @Test
  void blankStdinRunsNothingAndExitsZero() {
    // jq-ref 1.8.2, observed 2026-11-11: printf ' \t\r\n' | jq-ref -c . -> rc 0, no output; so does empty stdin.
    for (String stdin : List.of("", " \t\r\n")) {
      assertThat(run(List.of("-c", "."), stdin)).as("-c . on %s", stdin.isEmpty() ? "empty stdin" : "SP TAB CR LF").isEqualTo(new Outcome(0, "", ""));
    }
    // Witness: a document after the whitespace prints.
    assertThat(run(List.of("-c", "."), " \t\r\n1")).as("-c . on whitespace and 1").isEqualTo(new Outcome(0, "1\n", ""));
  }

  @Test
  void aFileArgumentIsReadInsteadOfStdin() throws IOException {
    // jq-ref 1.8.2, observed 2026-11-11: printf '{"a":[1, 2]}\n' > in.json; jq-ref -c . in.json -> {"a":[1,2]} (rc 0).
    Files.write(cwd.resolve("in.json"), "{\"a\":[1, 2]}\n".getBytes(UTF_8));
    assertThat(run(List.of("-c", ".", "in.json"), "[9]")).as("-c . in.json, with [9] on stdin").isEqualTo(new Outcome(0, "{\"a\":[1,2]}\n", ""));
    // Witness: without the file, stdin is read.
    assertThat(run(List.of("-c", "."), "[9]")).as("-c . with [9] on stdin").isEqualTo(new Outcome(0, "[9]\n", ""));
  }

  @Test
  void aMissingFileGivesJqsTextAndExitsTwo() {
    // jq-ref 1.8.2, recorded in diff/cli.rec (cli/missing-file): jq-ref -c .a missing.json -> rc 2, stdout empty,
    //   jq: error: Could not open file missing.json: No such file or directory
    assertThat(run(List.of("-c", ".", "missing.json"), "[9]"))
        .isEqualTo(new Outcome(2, "", "jq: error: Could not open file missing.json: No such file or directory\n"));
  }

  @Test
  void aSecondFileIsAStubWithExitTwo() throws IOException {
    // A second file is still a Stub (several files are spec 5's); jq-ref 1.8.2, observed 2026-11-11, reads it as more
    // input: jq-ref -c .a in.json in.json -> 1 and 1, rc 0.
    Files.write(cwd.resolve("in.json"), "[1]".getBytes(UTF_8));
    Outcome o = run(List.of("-c", ".", "in.json", "in.json"), "[9]");
    assertThat(o.rc()).as("exit code").isEqualTo(2);
    assertThat(o.out()).as("stdout").isEmpty();
    assertThat(o.err()).as("stderr").startsWith(MARK).endsWith("\n");
  }

  @Test
  void aParseErrorOfTheInputExitsFive() {
    // jq-ref 1.8.2, observed 2026-11-11: 10 001 '[' on stdin under -c . -> rc 5 and
    // "jq: parse error: Exceeds depth limit for parsing at line 1, column 10001".
    assertThat(run(List.of("-c", "."), "[".repeat(10_001)))
        .isEqualTo(new Outcome(5, "", "jq: parse error: Exceeds depth limit for parsing at line 1, column 10001\n"));
  }

  @Test
  void aFailingStdinIsReportedNotSwallowed() {
    IOException broken = new IOException("stdin closed");
    InputStream stdin =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw broken;
          }
        };
    assertThatThrownBy(() -> run(List.of("-c", "."), stdin))
        .isInstanceOfSatisfying(UncheckedIOException.class, e -> assertThat(e.getCause()).as("the cause").isSameAs(broken));
  }
}
