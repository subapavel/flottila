package io.github.subapavel.jqj.query.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The agent-free child JVM of the stack claims (F-44, after the IrDepthTest harness of the engine module): a warm or
 * instrumented JVM can need more stack than a fresh one, so a 512 KiB claim is pinned in one fresh child JVM, started
 * with this JVM's class path, no agent and no tool or JVM options, the case a host meets.
 */
final class FreshJvm {
  /** ADR-0017's default stack for a run. */
  static final long STACK = 512 * 1024;

  private FreshJvm() {}

  /** What {@code main} printed for {@code scenario} in a fresh child JVM, which must end within 120 s with exit code 0. */
  static String printed(Path dir, Class<?> main, String scenario) throws IOException, InterruptedException {
    File out = dir.resolve(scenario + ".out").toFile();
    ProcessBuilder launch =
        new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), main.getName(), scenario)
            .redirectErrorStream(true)
            .redirectOutput(out);
    // Each would reach the child's command line, and an agent there would make it the instrumented case.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("the child JVM ends within 120 s").isTrue();
      String printed = Files.readString(out.toPath(), UTF_8);
      assertThat(child.exitValue()).as("exit code of the child JVM, which printed: %s", printed).isZero();
      return printed;
    } finally {
      child.destroyForcibly();
    }
  }

  /** Prints {@code name}, then what {@code work} gave on a thread of {@link #STACK} bytes, or what it threw there. */
  static void print(String name, Supplier<String> work) throws InterruptedException {
    AtomicReference<String> got = new AtomicReference<>();
    Throwable thrown = on(STACK, () -> got.set(work.get()));
    System.out.println(name + ": " + (thrown == null ? got.get() : thrown));
  }

  /** What {@code work} threw on a thread of {@code stack} bytes, or null. */
  static Throwable on(long stack, Runnable work) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                work.run();
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            stack + " bytes",
            stack);
    thread.start();
    thread.join();
    return failure.get();
  }
}
