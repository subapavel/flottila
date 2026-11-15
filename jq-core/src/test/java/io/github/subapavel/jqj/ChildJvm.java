package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A fresh, agent-free child JVM, factored out of #111's DeepStackFreshJvmTest (ADR-0046 and its #113 amendment, F-91):
 * {@code <java.home>/bin/java}, the tier's flags, this JVM's class path and the main class. {@code JAVA_TOOL_OPTIONS},
 * {@code JDK_JAVA_OPTIONS} and {@code _JAVA_OPTIONS} are removed from its environment, so no agent and no {@code -Xmx}
 * reaches it; it is not started under a ulimit. A child ends within its time with exit code 0, or the test fails with
 * what it printed. The time is a wall-clock guard against a hung child, not an assertion: {@value #SECONDS} s, and
 * {@value #MATRIX_SECONDS} s for the linked and search children of {@code CalibrationTest}'s matrix, whose first
 * unseeded {@code -Xint} linked child of the nested-driver shape needed 271 s on an idle 4-CPU box (#113): ×3.3
 * headroom.
 *
 * <p>A child that measures a stack runs its work on the launcher's main thread, sized by {@code -Xss}: the process's
 * first thread, which glibc's stack cache cannot serve with a larger stack an ended thread left (F-93, D2).
 */
final class ChildJvm {
  /** The time a child has (the #113 design). */
  static final long SECONDS = 300;

  /** The time a linked or search child of the calibration matrix has: 271 s measured idle (#113), ×3.3 headroom. */
  static final long MATRIX_SECONDS = 900;

  private ChildJvm() {}

  /** What {@code main} printed, run in a fresh JVM with {@code flags} and {@code args} within {@value #SECONDS} s. */
  static String run(Path dir, List<String> flags, Class<?> main, String... args) throws IOException, InterruptedException {
    return run(dir, SECONDS, flags, main, args);
  }

  /** What {@code main} printed, run in a fresh JVM with {@code flags} and {@code args} within {@code seconds}. */
  static String run(Path dir, long seconds, List<String> flags, Class<?> main, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(flags);
    command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
    command.addAll(List.of(args));
    return command(dir, seconds, command);
  }

  /** What {@code command} printed, stdout and stderr together, once it ended within {@value #SECONDS} s with exit code 0. */
  static String command(Path dir, List<String> command) throws IOException, InterruptedException {
    return command(dir, SECONDS, command);
  }

  /** What {@code command} printed, stdout and stderr together, once it ended within {@code seconds} with exit code 0. */
  private static String command(Path dir, long seconds, List<String> command) throws IOException, InterruptedException {
    File out = Files.createTempFile(dir, "child", ".out").toFile();
    ProcessBuilder launch = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out);
    // Each would reach the child's command line, and an agent there would make it the instrumented case.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    String shown = String.join(" ", command).replace(System.getProperty("java.class.path"), "<class path>");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(seconds, TimeUnit.SECONDS)).as("%s ends within %d s", shown, seconds).isTrue();
      String printed = Files.readString(out.toPath(), UTF_8);
      assertThat(child.exitValue()).as("the exit code of %s, which printed %s", shown, printed).isZero();
      return printed;
    } finally {
      child.destroyForcibly();
    }
  }
}
