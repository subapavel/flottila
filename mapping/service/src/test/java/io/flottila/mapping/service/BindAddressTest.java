package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 1 has no authentication, so the service warns at startup when it listens on anything
 * but loopback (ADR-0014, "Running"). The warning is observed as a log record at {@code WARNING} or
 * above that names the host, on the calling thread, through the root logger.
 */
class BindAddressTest {

  static Stream<Arguments> hosts() {
    return Stream.of(
        Arguments.of("127.0.0.1", false),
        Arguments.of("::1", false),
        Arguments.of("localhost", false),
        Arguments.of("0.0.0.0", true),
        Arguments.of("::", true),
        Arguments.of("192.0.2.10", true));
  }

  @ParameterizedTest(name = "{0} warns: {1}")
  @MethodSource("hosts")
  void warnsUnlessLoopback(String host, boolean warns) {
    List<String> warnings = warningsWhile(() -> ServiceConfig.warnUnlessLoopback(host));

    if (warns) {
      assertThat(warnings).hasSize(1);
      assertThat(warnings.getFirst()).contains(host);
    } else {
      assertThat(warnings).isEmpty();
    }
  }

  /** The text and parameters of every record at WARNING or above logged on this thread. */
  private static List<String> warningsWhile(Runnable action) {
    Logger root = Logger.getLogger("");
    long thread = Thread.currentThread().threadId();
    List<String> warnings = new ArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLongThreadID() == thread
                && record.getLevel().intValue() >= Level.WARNING.intValue()) {
              Object[] parameters = record.getParameters();
              warnings.add(
                  record.getMessage()
                      + (parameters == null ? "" : " " + Arrays.toString(parameters)));
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    root.addHandler(capture);
    try {
      action.run();
    } finally {
      root.removeHandler(capture);
    }
    return warnings;
  }
}
