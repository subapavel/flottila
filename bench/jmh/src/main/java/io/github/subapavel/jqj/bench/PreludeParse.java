package io.github.subapavel.jqj.bench;

import io.github.subapavel.jqj.internal.parse.Parser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The cold parse of the prelude (#71, ADR-0042): one {@link Parser#parse} of jq's {@code builtin.jq} in a fresh JVM,
 * the work the prelude's holder class does once per JVM on the first unknown name. Single-shot mode, 10 forks, no
 * warm-up, G7's heap. The results are in bench/RESULTS.md. Not a gate scenario: no bench id of ADR-0024.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
    value = 10,
    jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 0)
@Measurement(iterations = 1, batchSize = 1)
@Threads(1)
public class PreludeParse {

  /** Parse the prelude's text; the Program is returned so JMH consumes it. */
  @Benchmark
  public Object prelude_cold_parse(Text s) {
    return Parser.parse(s.text);
  }

  /** Only the prelude's text exists before the shot; no class of the parser is loaded by the setup. */
  @State(Scope.Thread)
  public static class Text {
    /** The resource as jq-core's jar carries it; the benchmarks jar runs on the class path (ADR-0013). */
    static final String RESOURCE = "io/github/subapavel/jqj/internal/parse/builtin.jq";

    String text;

    /** A missing resource fails the setup (a null stream), so no shot is timed without the text. */
    @Setup
    public void setUp() throws IOException {
      try (InputStream in = ClassLoader.getSystemResourceAsStream(RESOURCE)) {
        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }
}
