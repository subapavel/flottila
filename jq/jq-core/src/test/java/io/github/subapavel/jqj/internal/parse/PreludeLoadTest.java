package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ADR-0042 "Cache" and spec 2a "Prelude": builtin.jq is parsed once per JVM, on the first compile that names a
 * non-native builtin, and never for a filter that names none, so G7 ({@code .a}) does not pay. {@link Prelude#loads()}
 * counts the parses. Below the API because the API cannot see a parse (spec 2a, "Rules for the red": the seams cannot
 * reach it). A JVM that has already loaded the prelude cannot show that {@code .a} does not, so the main case runs in a
 * fresh JVM ({@link PreludeLoadProbe}); {@code length} and {@code empty} are natives, found before the prelude (C1).
 */
class PreludeLoadTest {

  @Test
  void aFreshJvmParsesThePreludeOnlyAtTheFirstNonNativeBuiltinAndOnce() throws IOException, InterruptedException {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    ProcessBuilder launch =
        new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), PreludeLoadProbe.class.getName())
            .redirectErrorStream(true);
    // A JVM started with one of these set prints "Picked up ..." on stderr, which a build host may set (IrDepthTest).
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process p = launch.start();
    p.getOutputStream().close();
    boolean ended = p.waitFor(120, TimeUnit.SECONDS);
    if (!ended) p.destroyForcibly();
    String out = new String(p.getInputStream().readAllBytes(), UTF_8);
    assertThat(ended).as("the probe ended within 120 s; its output so far:%n%s", out).isTrue();
    assertThat(p.exitValue()).as("exit code of the probe; its output:%n%s", out).isZero();
    assertThat(out.lines().toList())
        .as("Prelude.loads() before any compile and after each of %s", PreludeLoadProbe.FILTERS)
        .isEqualTo(List.of("start 0", ".a 0", "empty 0", ".a|length 0", "map(.) 1", "map(.) 1"));
  }

  /** In this JVM, whatever ran before: after two compiles of {@code map(.)} the prelude has been parsed exactly once. */
  @Test
  void twoCompilesOfMapLeaveOneParse() {
    JqEngine engine = JqEngine.builder().build();
    engine.compile("map(.)");
    engine.compile("map(.)");
    assertThat(Prelude.loads()).as("Prelude.loads() after map(.) twice").isEqualTo(1);
  }

  /** A second engine shares the JVM's prelude: it parses nothing again (ADR-0042, one parse per JVM, not per Engine). */
  @Test
  void aSecondEngineDoesNotParseAgain() {
    JqEngine.builder().build().compile("map(.)");
    JqEngine.builder().build().compile("[.[] | select(.)]");
    assertThat(Prelude.loads()).as("Prelude.loads() after map and select on two engines").isEqualTo(1);
  }
}
