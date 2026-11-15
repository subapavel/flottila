package io.flottila.mapping.bench;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.runtime.MalformedInputException;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The {@code orders} benchmarks of ADR-0021, end to end through {@code engine/api}: input bytes in,
 * output bytes out. The scenarios {@code scripts/ratchet.java} reads from {@code
 * <Mapping>Bench.<method>} (#184): {@code smallLatency} is {@code orders/small/latency}, and its
 * {@code -prof gc} metric {@code gc.alloc.rate.norm} is {@code orders/small/alloc}; {@code
 * largeThroughput} is {@code orders/large/throughput}, in MB/s from its param {@code inputBytes}
 * and its time per run, and its {@code gc.alloc.rate.norm} is {@code orders/large/alloc}; {@code
 * largeHeap} is {@code orders/large/heap}, which only has to complete; {@code compile} is {@code
 * compile/orders}. The defaults are the ratchet's runs (ADR-0021): three forks of three warm-up and
 * five measured iterations of one second; the command line overrides them. The large benchmarks fix
 * their forks' heap with {@code jvmArgsAppend}, which holds while the command line passes no {@code
 * -jvmArgsAppend} (#181 decision 2): 1 GB for the throughput and its allocation, 128 MB for the
 * heap proof.
 */
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class OrdersBench {

  /** The small input and the mapping compiled once, as a deploy compiles it. */
  @State(Scope.Benchmark)
  public static class Small {
    EngineFacade facade;
    CompiledMapping compiled;
    byte[] input;

    @Setup(Level.Trial)
    public void setUp() throws MalformedJsonException {
      BenchMapping orders = BenchMapping.orders();
      facade = new EngineFacade();
      compiled = orders.compile(facade, CompileOptions.DEPLOY);
      input = orders.small();
    }
  }

  /**
   * The exact byte length of the large input, pinned with its digest by {@code OrdersInputTest}.
   */
  static final String LARGE_BYTES = "10486142";

  /** The large input, generated from the seed at setup, and the mapping compiled once. */
  @State(Scope.Benchmark)
  public static class Large {
    /**
     * The large input's exact byte length, which {@code scripts/ratchet.java bench} reads from the
     * JMH JSON for the MB/s; the setup refuses an input of any other length.
     */
    @Param(LARGE_BYTES)
    int inputBytes;

    EngineFacade facade;
    CompiledMapping compiled;
    byte[] input;

    /**
     * Compiles the mapping and generates the large input.
     *
     * @throws IllegalStateException when the input's length is not {@code inputBytes}: the MB/s
     *     would be wrong
     */
    @Setup(Level.Trial)
    public void setUp() throws MalformedJsonException {
      BenchMapping orders = BenchMapping.orders();
      facade = new EngineFacade();
      compiled = orders.compile(facade, CompileOptions.DEPLOY);
      input = orders.large();
      if (input.length != inputBytes) {
        throw new IllegalStateException(
            "the large input has " + input.length + " bytes, the param inputBytes " + inputBytes);
      }
    }
  }

  /** The model's bytes and its resolver, whose schema files are in memory. */
  @State(Scope.Benchmark)
  public static class Model {
    EngineFacade facade;
    byte[] model;
    ReferenceResolver resolver;

    @Setup(Level.Trial)
    public void setUp() throws MalformedJsonException {
      BenchMapping orders = BenchMapping.orders();
      facade = new EngineFacade();
      model = orders.model();
      resolver = orders.resolver();
      // reads the schema files into the resolver's memory, and proves that the model compiles
      orders.compile(facade, CompileOptions.TEST_RUN);
    }
  }

  /** {@code orders/small/latency}: one run of the small input, µs/op. */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public byte[] smallLatency(Small small) throws MalformedInputException {
    return small.facade.run(small.compiled, small.input);
  }

  /**
   * {@code orders/large/throughput} and {@code orders/large/alloc}: one run of the large input,
   * ms/op, in a fork with a fixed 1 GB heap.
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Fork(
      value = 3,
      jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
  public byte[] largeThroughput(Large large) throws MalformedInputException {
    return large.facade.run(large.compiled, large.input);
  }

  /**
   * {@code orders/large/heap}: the same run in a fork with a 128 MB heap. It completes, or the
   * benchmark fails; its time is not judged.
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Fork(
      value = 3,
      jvmArgsAppend = {"-Xms128m", "-Xmx128m"})
  public byte[] largeHeap(Large large) throws MalformedInputException {
    return large.facade.run(large.compiled, large.input);
  }

  /**
   * {@code compile/orders}: the service's compile of a test run, ms/op, from the model's bytes, the
   * structure read included.
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public CompileResult compile(Model model) throws MalformedJsonException {
    return model.facade.compile(model.model, model.resolver, CompileOptions.TEST_RUN);
  }
}
