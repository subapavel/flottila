package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqFilter;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.bench.Gates.Discard;
import io.github.subapavel.jqj.bench.Inputs.Size;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The walk-side key proof's cost (#85, ADR-0044), with ADR-0019's operation and settings: ADR-0019's wide-iterate
 * diagnostic ({@code .[]} and {@code -c .} on a flat root object of 78 841 keys, ReaderCost's document of #82), G1, G2
 * and {@code -c .} (G6's filter) on orders at 1k, 100k and 10m, and {@code recs20}, an array of 20-key records, under
 * {@code -c .} and {@code .[]|.[]}. Not a gate scenario: no bench id of ADR-0024. {@link #main} writes the flat and
 * {@code recs20} documents for jq-ref. The results are in bench/RESULTS.md, "#85".
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
public class KeyProofCost {
  /** The cell: scenario, then the document's size. */
  @Param({
    "wide-iterate", "wide-print",
    "G1.1k", "G1.100k", "G1.10m",
    "G2.1k", "G2.100k", "G2.10m",
    "G6.1k", "G6.100k", "G6.10m",
    "recs20-print.1k", "recs20-print.100k", "recs20-print.10m",
    "recs20-iterate.1k", "recs20-iterate.100k", "recs20-iterate.10m"
  })
  public String cell;

  private final Discard out = new Discard();
  private JqFilter filter;
  private Workspace workspace;
  private MemorySegment input;

  @Setup
  public void setUp() {
    String[] parts = cell.split("\\.", 2);
    Size size = parts.length == 1 ? null : size(parts[1]);
    String text;
    byte[] document;
    switch (parts[0]) {
      case "wide-iterate" -> {
        text = ".[]";
        document = flat(78_841);
      }
      case "wide-print" -> {
        text = ".";
        document = flat(78_841);
      }
      case "G1" -> {
        text = ".a.b.c";
        document = Inputs.orders(size);
      }
      case "G2" -> {
        text = Gates.G2;
        document = Inputs.orders(size);
      }
      case "G6" -> {
        text = ".";
        document = Inputs.orders(size);
      }
      case "recs20-print" -> {
        text = ".";
        document = recs20(size.bytes());
      }
      case "recs20-iterate" -> {
        text = ".[]|.[]";
        document = recs20(size.bytes());
      }
      default -> throw new IllegalArgumentException(cell);
    }
    JqEngine engine = JqEngine.builder().limits(Limits.embedded()).build();
    filter = engine.compile(text);
    workspace = engine.newWorkspace();
    input = MemorySegment.ofArray(document);
    operation();
  }

  /** ADR-0019's operation, in compact output. */
  @Benchmark
  public long operation() {
    return Gates.once(filter, workspace, input, OutputOptions.compact(), out);
  }

  private static Size size(String label) {
    for (Size s : Size.values()) {
      if (s.label().equals(label)) {
        return s;
      }
    }
    throw new IllegalArgumentException(label);
  }

  /** {@code {"k000000":0,"k000001":1,…}} and an LF: {@link ReaderCost#flat}'s object of {@code keys} keys (#82). */
  static byte[] flat(int keys) {
    StringBuilder doc = new StringBuilder(keys * 16).append('{');
    for (int i = 0; i < keys; i++) {
      if (i > 0) {
        doc.append(',');
      }
      doc.append(String.format("\"k%06d\":%d", i, i));
    }
    return doc.append("}\n").toString().getBytes(US_ASCII);
  }

  /**
   * As many records as fit in {@code bytes} with the array's brackets and one LF: record {@code i} has the keys
   * {@code f00} to {@code f19}, an even one with the number {@code i * 20 + f}, an odd one with the string
   * {@code "v<i>"}, then spaces up to exactly {@code bytes}.
   */
  static byte[] recs20(int bytes) {
    StringBuilder doc = new StringBuilder(bytes).append('[');
    for (int i = 0; ; i++) {
      StringBuilder record = new StringBuilder(256).append('{');
      for (int f = 0; f < 20; f++) {
        if (f > 0) {
          record.append(',');
        }
        record.append(String.format("\"f%02d\":", f));
        if (f % 2 == 0) {
          record.append(i * 20 + f);
        } else {
          record.append("\"v").append(i).append('"');
        }
      }
      record.append('}');
      int comma = i == 0 ? 0 : 1;
      if (doc.length() + comma + record.length() + 2 > bytes) {
        break;
      }
      if (comma == 1) {
        doc.append(',');
      }
      doc.append(record);
    }
    doc.append(']');
    doc.repeat(' ', bytes - 1 - doc.length()).append('\n');
    return doc.toString().getBytes(US_ASCII);
  }

  /**
   * Writes {@code wide-78841.json} and {@code recs20-<size>.json} into the directory {@code args[0]}.
   *
   * @throws IOException if a file cannot be written
   */
  public static void main(String[] args) throws IOException {
    Path dir = Files.createDirectories(Path.of(args[0]));
    Files.write(dir.resolve("wide-78841.json"), flat(78_841));
    for (Size s : Size.values()) {
      Files.write(dir.resolve("recs20-" + s.label() + ".json"), recs20(s.bytes()));
    }
  }
}
