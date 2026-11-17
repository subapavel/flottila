package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A long {@code .a.a.a…} chain compiles and runs without a StackOverflowError (#70 design approval, O3): the parser
 * reads the postfix steps in a loop, so the Resolver and the run meet a chain as long as the filter. On null each
 * {@code .a} is null, so the result is one null. jq-ref 1.8.2 (observed 2026-11-13) prints null for 21 844 steps and
 * rejects 21 845 with {@code function compiled to 65537 bytes which is too long}, rc 3; 10⁵ steps is past jq's
 * bytecode limit, as the long comma chains of {@code CommaChainTest} are (DEVIATIONS "Long comma chains").
 */
class LongPathChainTest {

  /** ADR-0017's default stack for a run, as {@code CommaChainTest} takes it (#70 design approval, open question 2). */
  private static final long STACK = 512 * 1024;

  /** One read more than the Resolver keeps as steps of their own (F-42): the run is one fused step. */
  private static final int FUSED = 513;

  @ParameterizedTest(name = "{0} steps")
  @ValueSource(ints = {21_844, 100_000})
  void aLongFieldChainCompilesAndRunsOnTheTestThread(int steps) {
    assertThat(results(".a".repeat(steps))).as("results of %d steps of .a on null", steps).isEqualTo(List.of("null"));
  }

  @ParameterizedTest(name = "{0} steps on a 512 KiB thread")
  @ValueSource(ints = {21_844, 100_000})
  void aLongFieldChainCompilesAndRunsOnA512KiBThread(int steps) throws InterruptedException {
    assertThat(onA512KiBThread(".a".repeat(steps))).as("results of %d steps of .a on null on a 512 KiB thread", steps).isEqualTo(List.of("null"));
  }

  /**
   * Every other step kind of a postfix chain, 10⁵ steps on null on a 512 KiB thread (F-44): jq-ref 1.8.2, observed
   * 2026-11-13 with -nc on two steps of each, gives null for .[0], .["k"], .[1:2] and .[0]??, no result for .[]?, and
   * the error of .[] caught. With -nc -f, 21 844 steps of .[0] give null (rc 0) and 21 845 its bytecode limit, rc 3
   * (DEVIATIONS "Long path chains").
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "'.' | [0] | '' | null",
        "'.' | [\"k\"] | '' | null",
        "'.' | [1:2] | '' | null",
        "'.' | [0]?? | '' | null",
        "'[.' | []? | ']' | []",
        "'try (.' | [] | ') catch .' | \"Cannot iterate over null (null)\""
      })
  void aLongChainOfEachStepKindCompilesAndRunsOnA512KiBThread(String before, String step, String after, String result) throws InterruptedException {
    String filter = before + step.repeat(100_000) + after;
    assertThat(onA512KiBThread(filter)).as("results of 10⁵ steps of %s on null on a 512 KiB thread", step).isEqualTo(List.of(result));
  }

  /**
   * F-44: a chain long enough to be fused or piped balanced ticks as the same chain cut in pieces, which are short Pipe
   * chains, the nested form: the fewest steps each runs under are equal, and more than {@code .} runs under on the same
   * input. Each input is JSON text nesting 513 deep, read as a view, which charges the entries a step reads
   * (ADR-0034), so every piece of the chain ticks.
   */
  @ParameterizedTest(name = "{2}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "'{\"b\":0,\"a\":' | '}' | .a",
        "'[0,' | ']' | [1]",
        "'[0,' | ']' | [1:2][0]",
        "'[' | ']' | []",
        "'[' | ']' | []?",
        "'[0,' | ']' | [1]??"
      })
  void aLongChainTicksAsTheNestedFormDoes(String open, String close, String step) {
    byte[] input = (open.repeat(FUSED) + "7" + close.repeat(FUSED)).getBytes(StandardCharsets.UTF_8);
    String chain = chain(step, FUSED);
    // Pieces of 30 steps, at most 60 nodes: none has more than the Resolver's PIPE_CHAIN of 64, so none is fused or
    // balanced.
    String parts = pieces(step, FUSED, 30);
    long whole = fewestSteps(chain, input);
    assertThat(whole).as("fewest steps of %s", Inlining.shown(chain)).isEqualTo(fewestSteps(parts, input));
    assertThat(whole).as("fewest steps of %s against .'s", Inlining.shown(chain)).isGreaterThan(fewestSteps(".", input));
  }

  /** {@code n} steps {@code step} on the input: after a leading dot unless the step starts with its own. */
  private static String chain(String step, int n) {
    return (step.startsWith(".") ? "" : ".") + step.repeat(n);
  }

  /** {@code n} steps {@code step} in parenthesised pieces of {@code per} steps, the last of the rest, piped. */
  private static String pieces(String step, int n, int per) {
    StringJoiner piped = new StringJoiner(" | ");
    for (int done = 0; done < n; done += per) {
      piped.add("(" + chain(step, Math.min(per, n - done)) + ")");
    }
    return piped.toString();
  }

  /** The fewest steps a run of {@code filter} on the JSON text {@code input} ends under, by bisection. */
  private static long fewestSteps(String filter, byte[] input) {
    long low = 1;
    long high = 1L << 24;
    while (low < high) {
      long mid = (low + high) >>> 1;
      if (endsUnder(filter, input, mid)) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return low;
  }

  private static boolean endsUnder(String filter, byte[] input, long steps) {
    JqEngine engine = RunBudgets.steps(steps);
    Workspace workspace = engine.newWorkspace();
    try {
      engine.compile(filter).run(RunBudgets.read(workspace, input).root(), RunBudgets.NONE, v -> true, workspace);
      return true;
    } catch (JqTermination e) {
      return false;
    }
  }

  /** The results of {@code filter} on null, compiled and run on a thread of ADR-0017's 512 KiB stack. */
  private static List<String> onA512KiBThread(String filter) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<List<String>> got = new AtomicReference<>();
    Thread small = new Thread(null, () -> {
      try {
        got.set(results(filter));
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "512 KiB", STACK);
    small.start();
    small.join();
    assertThat(failure.get()).as("what %s threw on a 512 KiB thread", Inlining.shown(filter)).isNull();
    return got.get();
  }

  /** jq-ref 1.8.2, observed 2026-11-13 with -nc -f: 7, rc 0. Each read of the fused run reads its predecessor's result. */
  @Test
  void aFusedRunReadsEachFieldOfThePreviousResult() {
    String filter = "reduce range(0; " + FUSED + ") as $i (7; {a: .}) | " + ".a".repeat(FUSED);
    assertThat(results(filter)).as("results of %d reads into %d nested objects", FUSED, FUSED).isEqualTo(List.of("7"));
  }

  /**
   * jq-ref 1.8.2, observed 2026-11-13 with -nc -f: []. The quiet second read has no result on 1, so the run ends there:
   * the plain reads after it never meet the missing value.
   */
  @Test
  void aQuietReadWithoutAResultEndsTheFusedRun() {
    String filter = "[{\"a\": 1} | .a.a?" + ".a".repeat(FUSED - 2) + "]";
    assertThat(results(filter)).as("results of a fused run whose quiet read has no result").isEqualTo(List.of("[]"));
  }

  /**
   * jq-ref 1.8.2, observed 2026-11-13 with -nc -f: 7. The fused run comes after a step of the same chain, so it spans
   * its own reads only.
   */
  @Test
  void aFusedRunAfterAnIterateReadsEachResult() {
    String filter = "[reduce range(0; " + FUSED + ") as $i (7; {a: .})] | .[]" + ".a".repeat(FUSED);
    assertThat(results(filter)).as("results of %d reads after .[]", FUSED).isEqualTo(List.of("7"));
  }

  /**
   * A field named by a string with an interpolation is a step whose key is bound around it (F-44), plain or quiet:
   * jq-ref 1.8.2, observed 2026-11-13 with -nc, gives each result here.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "'{\"ab\":1} | .\"a\\(\"b\")\"' | 1",
        "'{\"ab\":1} | .\"a\\(\"b\")\"?' | 1",
        "'[1 | .\"a\\(\"b\")\"?]' | []",
        "'try (1 | .\"a\\(\"b\")\") catch .' | '\"Cannot index number with string (\\\"ab\\\")\"'"
      })
  void aFieldNamedByAnInterpolationIsAStep(String filter, String result) {
    assertThat(results(filter)).as("results of %s on null", filter).isEqualTo(List.of(result));
  }

  /**
   * A quiet step after another, whose IR ResolverTest pins as one Try around both (F-44): jq-ref 1.8.2, observed
   * 2026-11-13 with -nc, gives each result here. On 1 the quiet read has no result, so the read piped after it never
   * runs.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {"'.[0]??[0]??' | null", "'[.[0]??[.a]??]' | []", "'[1 | .[0]?? | .a]' | []"})
  void aQuietStepAfterAnotherRunsAsJqDoes(String filter, String result) {
    assertThat(results(filter)).as("results of %s on null", filter).isEqualTo(List.of(result));
  }

  /** jq-ref 1.8.2, observed 2026-11-13 with -nc -f: the second read's error, caught. */
  @Test
  void aPlainReadThatFailsRaisesFromTheFusedRun() {
    String filter = "try ({\"a\": 1} | " + ".a".repeat(FUSED) + ") catch .";
    assertThat(results(filter)).as("results of a fused run whose second read fails").isEqualTo(List.of("\"Cannot index number with string (\\\"a\\\")\""));
  }
}
