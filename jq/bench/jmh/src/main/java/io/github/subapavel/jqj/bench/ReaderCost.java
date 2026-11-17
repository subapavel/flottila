package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JsonDoc;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.Workspace;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The reader's cost bar of #82 (ADR-0002 as amended by F-40, withdrawn): one read of a document onto a reused
 * Workspace, its tape (and on #82's reader its proof table) warmed by a read in setup. The documents are Spike B's at
 * 1 KB, 100 KB and 10 MB (bench/RESULTS.md "Spike B": {@code Docs.make} on {@code spike/field-read}, the field in the
 * middle), whose root objects have 8, 770 and 78 841 keys, and a flat object of 78 841 keys and small integers. The
 * settings are Spike B's. The results are in bench/RESULTS.md, "#82". Not a gate scenario: no bench id of ADR-0024.
 * Ported by #85 from {@code t/82-distinct-keys} at a01fda2, which is deleted at #85's merge.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 2,
    jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@State(Scope.Thread)
public class ReaderCost {
  /** The document read. */
  @Param({"spikeB-1k", "spikeB-100k", "spikeB-10m", "flat-78841"})
  public String doc;

  private Workspace workspace;
  private MemorySegment input;

  @Setup
  public void setUp() {
    input =
        MemorySegment.ofArray(
            switch (doc) {
              case "spikeB-1k" -> spikeB(1 << 10);
              case "spikeB-100k" -> spikeB(100 << 10);
              case "spikeB-10m" -> spikeB(10 << 20);
              case "flat-78841" -> flat(78_841);
              default -> throw new IllegalArgumentException(doc);
            });
    workspace = JqEngine.builder().limits(Limits.embedded()).build().newWorkspace();
    reader();
  }

  /** One read; the root is returned for JMH to consume. */
  @Benchmark
  public Object reader() {
    try (JsonDoc read = workspace.read(input)) {
      return read.root();
    }
  }

  /** Spike B's record {@code i} ({@code Docs.record}): 6 keys, escapes and non-ASCII, nested containers. */
  static String record(int i) {
    return "{\"id\":"
        + (100_000 + i)
        + ",\"name\":\"item \\\""
        + i
        + "\\\" über\",\"tags\":[\"a\",\"b\",\"c\"],\"amount\":"
        + (i % 1000)
        + "."
        + (i % 97)
        + "e1,\"ok\":"
        + (i % 2 == 0)
        + ",\"meta\":{\"n\":null,\"x\":[1,2,3],\"s\":\"}]\"}}";
  }

  /**
   * Spike B's document of about {@code bytes} ({@code Docs.make(bytes, "middle")}): a root object of records under the
   * keys {@code k000000}, {@code k000001}, …, with {@code "target":"value-42"} in the middle.
   */
  static byte[] spikeB(int bytes) {
    int per = record(0).length() + 10;
    int k = Math.max(2, bytes / per);
    int at = k / 2;
    StringBuilder doc = new StringBuilder(bytes + 256).append('{');
    for (int i = 0; i <= k; i++) {
      if (i == at) {
        if (doc.length() > 1) {
          doc.append(',');
        }
        doc.append("\"target\":\"value-42\"");
      }
      if (i == k) {
        break;
      }
      if (doc.length() > 1) {
        doc.append(',');
      }
      doc.append(String.format("\"k%06d\":", i)).append(record(i));
    }
    return doc.append('}').toString().getBytes(UTF_8);
  }

  /** {@code {"k000000":0,"k000001":1,…}}: an object of {@code keys} keys, each with its number. */
  static byte[] flat(int keys) {
    StringBuilder doc = new StringBuilder(keys * 16).append('{');
    for (int i = 0; i < keys; i++) {
      if (i > 0) {
        doc.append(',');
      }
      doc.append(String.format("\"k%06d\":%d", i, i));
    }
    return doc.append('}').toString().getBytes(UTF_8);
  }
}
