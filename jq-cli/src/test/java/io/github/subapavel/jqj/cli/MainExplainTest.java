package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code jqj --explain <filter>} (#108, ADR-0016): a jqj-only flag (DEVIATIONS "--explain"). It prints the API's
 * {@code explain()} text and exits 0 without reading input. The text is the API's, so it is compared with the API's
 * own, never with a written text.
 */
class MainExplainTest {
  private static final String FILTER = "def f: if . > 0 then . - 1 | f else . end; f";

  /** Stdin that fails the test when it is read. */
  private static final InputStream UNREAD =
      new InputStream() {
        @Override
        public int read() {
          throw new AssertionError("--explain read its input");
        }
      };

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(String... args) {
    return Main.run(new Invocation(List.of(args), Map.of(), Path.of("."), UNREAD, out, err));
  }

  private static String explain(String filter) {
    return JqEngine.builder().build().compile(filter).explain();
  }

  @Test
  void explainOnTheCommandLine() {
    int rc = run("--explain", FILTER);

    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo(explain(FILTER));
    assertThat(err.toByteArray()).as("stderr").isEmpty();
  }

  /** The flag may follow the filter and sit beside other options; a file named after it is not opened. */
  @Test
  void explainAfterTheFilterOpensNoFile() {
    int rc = run("-c", ".a", "--explain", "no-such-file.json");

    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo(explain(".a"));
    assertThat(err.toByteArray()).as("stderr").isEmpty();
  }

  /** Without a filter it explains {@code .}, as a run without one runs {@code .}. */
  @Test
  void explainWithoutAFilterExplainsDot() {
    int rc = run("--explain");

    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo(explain("."));
  }

  /** A stdout that fails is the entry's UncheckedIOException, as for a run's output. */
  @Test
  void aFailingStdoutIsAnUncheckedIoException() {
    OutputStream closed =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("stdout closed");
          }
        };
    Invocation invocation = new Invocation(List.of("--explain", "."), Map.of(), Path.of("."), UNREAD, closed, err);

    assertThatThrownBy(() -> Main.run(invocation)).isInstanceOf(UncheckedIOException.class).hasMessageContaining("stdout closed");
  }

  /** A filter that does not compile ends as it does without the flag: jq's compile error, exit code 3, no stdout. */
  @Test
  void explainOfAFilterThatDoesNotCompileIsTheCompileError() {
    int rc = run("--explain", "$x");

    assertThat(rc).as("exit code").isEqualTo(3);
    assertThat(out.toByteArray()).as("stdout").isEmpty();
    assertThat(err.toString(UTF_8)).as("stderr").contains("$x is not defined");
  }
}
