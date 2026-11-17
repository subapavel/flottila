package io.github.subapavel.jqj.bench;

import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.value.JqString;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The fixed charges of ADR-0035 (#64, F-39): each method builds one empty value as a run builds it, so
 * {@code -prof gc}'s {@code gc.alloc.rate.norm} is the bytes one built container or string allocates besides its
 * elements, entries or UTF-8 bytes. The results are in bench/RESULTS.md; they set {@code Charges.CONTAINER} and
 * {@code Charges.STRING}. Not a gate scenario: no bench id of ADR-0024.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
public class BuiltSizes {
  /** A shaped site's key list, shared by every object it builds. */
  private static final JqString[] SHAPE = {};

  /** A collected or sliced array: the array and its element slots, here none. */
  @Benchmark
  public Object sizes_array() {
    return new BuiltArray(new Object[0], 0);
  }

  /** An object of a shaped site: the object and its values, here none; the key list is the site's. */
  @Benchmark
  public Object sizes_shaped() {
    return new BuiltObject(SHAPE, new Object[0]);
  }

  /** A growable object: the arguments of its pairs, its own key list and its values, here none. */
  @Benchmark
  public Object sizes_growable() {
    return BuiltObject.ofPairs(new Object[0]);
  }

  /** A built string: the string and its bytes, here none. */
  @Benchmark
  public Object sizes_string() {
    return BuiltString.of(new byte[0]);
  }
}
