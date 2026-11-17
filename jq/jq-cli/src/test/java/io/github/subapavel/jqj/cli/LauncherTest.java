package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code package} writes (ADR-0013, spec story 51): the module path {@code target/lib/} and the launcher
 * {@code target/jqj}, which runs the command line from it. It needs the launcher test execution of this module's
 * build, after {@code package}; the system properties {@code jqj.launcher} and {@code jqj.lib} name the two.
 */
class LauncherTest {

  @TempDir Path dir;

  @Test
  void theModulePathHoldsTheThreeModules() throws IOException {
    Path lib = Path.of(System.getProperty("jqj.lib"));
    try (Stream<Path> jars = Files.list(lib)) {
      assertThat(jars.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).map(n -> n.replaceFirst("-[0-9].*", "")))
          .as("the jars in %s, without their versions", lib)
          .contains("jq-cli", "jq-core", "query-core");
    }
  }

  @Test
  void compactFieldOnAFilePrintsJqsOutput() throws IOException, InterruptedException {
    // jq-ref 1.8.2, recorded in diff/cli.rec (cli/compact-before-filter):
    //   printf '{"a":1}\n' > in.json; jq-ref -c .a in.json -> 1, rc 0, stderr empty.
    Files.write(dir.resolve("in.json"), "{\"a\":1}\n".getBytes(UTF_8));
    File out = dir.resolve("out").toFile();
    File err = dir.resolve("err").toFile();
    ProcessBuilder launch =
        new ProcessBuilder(System.getProperty("jqj.launcher"), "-c", ".a", "in.json")
            .directory(dir.toFile())
            .redirectInput(Redirect.from(new File("/dev/null")))
            .redirectOutput(out)
            .redirectError(err);
    // A JVM started with JAVA_TOOL_OPTIONS set prints "Picked up JAVA_TOOL_OPTIONS: ..." on stderr; a build host may set it.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    Process jqj = launch.start();

    assertThat(jqj.waitFor(60, TimeUnit.SECONDS)).as("the launcher ends within 60 s").isTrue();
    assertThat(Files.readString(err.toPath())).as("stderr").isEmpty();
    assertThat(jqj.exitValue()).as("exit code").isZero();
    assertThat(Files.readString(out.toPath())).as("stdout").isEqualTo("1\n");
  }
}
