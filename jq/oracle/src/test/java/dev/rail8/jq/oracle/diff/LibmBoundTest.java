package dev.rail8.jq.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The bound in ulp of each computed math builtin against jq's last digits, as {@code DEVIATIONS.md} "libm last digits"
 * gives it. A function's cases are {@code math-sample/<name>} and {@code math-computed/<name>-edges}; each prints one
 * array of numbers. jq's digits are the recording's; this engine's are the case's {@code engine-stdout} where its digits
 * differ (a {@code libm-digits} deviation) and the recording's where they agree, which the differential oracle checks
 * the engine gives. The bound is the largest distance in ulp over both cases, measured, so it is pinned exactly: a
 * NaN on one side only, or a case printing anything but one array, fails.
 */
class LibmBoundTest {

  /** Each computed function's measured bound in ulp. */
  private static final Map<String, Long> BOUNDS =
      Map.ofEntries(
          Map.entry("acos", 1L), Map.entry("acosh", 1L), Map.entry("asin", 1L), Map.entry("asinh", 1L),
          Map.entry("atan", 1L), Map.entry("atan2", 1L), Map.entry("atanh", 0L), Map.entry("cbrt", 2L),
          Map.entry("cos", 1L), Map.entry("cosh", 1L), Map.entry("erf", 1L), Map.entry("erfc", 2L),
          Map.entry("exp", 1L), Map.entry("exp10", 1L), Map.entry("exp2", 1L), Map.entry("expm1", 1L),
          Map.entry("hypot", 1L), Map.entry("log", 1L), Map.entry("log10", 1L), Map.entry("log1p", 0L),
          Map.entry("log2", 1L), Map.entry("pow", 1L), Map.entry("sin", 1L), Map.entry("sinh", 1L),
          Map.entry("tan", 1L), Map.entry("tanh", 0L));

  @Test
  void eachComputedFunctionStaysAtItsMeasuredBound() {
    Map<String, DiffCorpus.Entry> byId = new TreeMap<>();
    for (DiffCorpus.Entry e : Corpus.cases()) {
      byId.put(e.c().id(), e);
    }
    Map<String, Long> measured = new TreeMap<>();
    for (String name : BOUNDS.keySet()) {
      long worst = 0;
      for (String id : List.of("math-sample/" + name, "math-computed/" + name + "-edges")) {
        DiffCorpus.Entry e = byId.get(id);
        assertThat(e).as("the case %s", id).isNotNull();
        String deviation = e.c().deviation();
        assertThat(deviation == null || deviation.equals("libm-digits")).as("the deviation of %s", id).isTrue();
        Outcome ref = e.recording().outcome();
        worst = Math.max(worst, ulps(id, numbers(ref.stdout()), numbers(e.c().engine().over(ref).stdout())));
      }
      measured.put(name, worst);
    }
    assertThat(measured).as("the largest distance in ulp of each function over its cases").isEqualTo(new TreeMap<>(BOUNDS));
  }

  @Test
  void theDistanceCountsTheDoublesBetweenAcrossZero() {
    assertThat(ulps("t", new double[] {1, 0.0, -0.0, Double.MIN_VALUE, Double.NaN}, new double[] {Math.nextUp(1.0), -0.0, 0.0, -Double.MIN_VALUE, Double.NaN}))
        .isEqualTo(2);
    assertThat(ulps("t", new double[] {Double.MAX_VALUE}, new double[] {Math.nextDown(Double.MAX_VALUE)})).isEqualTo(1);
  }

  /** The largest distance in ulp between the numbers of {@code ref} and of {@code engine}, element by element. */
  private static long ulps(String id, double[] ref, double[] engine) {
    assertThat(engine.length).as("the number of results of %s", id).isEqualTo(ref.length);
    long worst = 0;
    for (int i = 0; i < ref.length; i++) {
      if (Double.isNaN(ref[i]) || Double.isNaN(engine[i])) {
        assertThat(Double.isNaN(engine[i])).as("whether result %d of %s is NaN", i, id).isEqualTo(Double.isNaN(ref[i]));
        continue;
      }
      worst = Math.max(worst, Math.abs(ordered(ref[i]) - ordered(engine[i])));
    }
    return worst;
  }

  /** The position of {@code d} on the line of doubles: neighbours differ by one, and both zeros are 0. */
  private static long ordered(double d) {
    long bits = Double.doubleToRawLongBits(d);
    return bits < 0 ? -(bits & Long.MAX_VALUE) : bits;
  }

  /** The numbers of one compact array on one line, a NaN for each {@code null}. */
  private static double[] numbers(byte[] stdout) {
    String text = new String(stdout, UTF_8);
    assertThat(text).as("an output of one array").startsWith("[").endsWith("]\n");
    List<Double> values = new ArrayList<>();
    String inner = text.substring(1, text.length() - 2);
    if (!inner.isEmpty()) {
      for (String part : inner.split(",", -1)) {
        values.add(part.equals("null") ? Double.NaN : Double.parseDouble(part));
      }
    }
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }
}
