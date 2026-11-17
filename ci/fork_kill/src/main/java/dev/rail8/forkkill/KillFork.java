package dev.rail8.forkkill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Ends the test JVM as soon as its first test finishes, the way a JVM that dies mid-run ends: with no goodbye to
 * Surefire or Failsafe and with exit code 0, so only the missing goodbye can tell the build that tests were lost.
 *
 * <p>The JUnit Platform loads it through {@code META-INF/services} when its jar is on the test class path. It does
 * nothing unless the system property {@code rail8.forkkill.dir} names a directory. Before it ends the JVM it appends one
 * line to the file {@code fork-<pid>} in that directory: the JVM's working directory (the module's base directory), a
 * tab, and the unique id of the test that finished.
 */
public final class KillFork implements TestExecutionListener {
  @Override
  public void executionFinished(TestIdentifier test, TestExecutionResult result) {
    String dir = System.getProperty("rail8.forkkill.dir");
    if (dir == null || !test.isTest()) {
      return;
    }
    String line = System.getProperty("user.dir") + "\t" + test.getUniqueId() + "\n";
    try {
      Files.writeString(Path.of(dir, "fork-" + ProcessHandle.current().pid()), line, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Runtime.getRuntime().halt(0);
  }
}
