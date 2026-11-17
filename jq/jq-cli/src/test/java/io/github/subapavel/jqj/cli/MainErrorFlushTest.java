package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The command line flushes stdout before it writes jq's error line (#34 design section 9), so the results before an
 * error reach a buffered stdout ahead of it.
 */
class MainErrorFlushTest {

  /** A stream that logs each flush and each write, as {@code <name> flush} and {@code <name> <text>}. */
  private static OutputStream logging(String name, List<String> log) {
    return new OutputStream() {
      @Override
      public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) {
        log.add(name + " " + new String(b, off, len, UTF_8));
      }

      @Override
      public void flush() {
        log.add(name + " flush");
      }
    };
  }

  @Test
  void stdoutIsFlushedBeforeTheErrorLine() {
    // jq-ref 1.8.2, recorded (field/error-array): .a on [1] LF -> rc 5, stdout empty, this stderr line.
    List<String> log = new ArrayList<>();
    Invocation invocation =
        new Invocation(List.of("-c", ".a"), Map.of(), Path.of("."), new ByteArrayInputStream("[1]\n".getBytes(UTF_8)), logging("out", log), logging("err", log));

    int rc = Main.run(invocation);

    assertThat(rc).as("exit code").isEqualTo(5);
    assertThat(log)
        .as("what reached stdout and stderr, in order")
        .containsExactly("out flush", "err jq: error (at <stdin>:1): Cannot index array with string (\"a\")\n");
  }
}
