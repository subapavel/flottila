package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0043: past {@code BIND_RUN} (= {@code PIPE_CHAIN}, 64) nested Binds an object, an interpolation, a pattern, a
 * value parameter's expansion (#93) and a run of one arithmetic or comparison operator (#94) lower to one Binds, which
 * ticks and charges nothing of its own, and whose values run in the order the nested form runs them; a run of more than
 * {@code PIPE_CHAIN} terms of {@code and}, {@code or} or {@code //} is joined balanced (#94). So each such construct
 * ticks as its nested form does: the fewest steps a run of n parts ends under lie on the line {@code a + b·n} the
 * nested form draws, at n = 63, 65 and 20 000, past jq's own limit and far past the F-44 bound under which the nested
 * form was built.
 *
 * <p>{@code BIND_RUN} counts the nested form's Binds, not parts: a part passed as it is, a Const, a Var or an Identity,
 * takes none, and a part may take more than one. So n = 63 and 65 straddle it only for a construct of one Bind a part,
 * such as the object of computed values; the object of computed keys (a key, a value and a check a pair), the object
 * pattern of computed keys and an arithmetic run of field reads (a term and an operator's result each; past it from 34
 * terms, 66 Binds) are one Binds at 63 already, and a run of variables, which binds only its operators' results, is
 * nested at both (61 and 63 Binds). n = 63 and 65 straddle {@code PIPE_CHAIN} for the {@code and}, {@code or} and
 * {@code //} runs. ResolverTest pins each threshold's two sides.
 *
 * <p>{@code a} and {@code b} were observed at 2fe9654 (#75's merge, before the Binds node of #92, the lowerings of #93
 * and #94 and the loops of #86, every construct a chain of nested Binds), in a scratch copy of that commit, by
 * {@link #fewestSteps} at n = 30 and n = 60: {@code b = (s60 − s30) / 30} and {@code a = s30 − 30·b} (#93's
 * constructs, each row's pair in its comment in {@link #constructs}; #94's runs, .logs/94-probe-2fe9654.log of the
 * test-author's run, where the same probe gave the line's value at n = 63 and 65 too). Each input is JSON text read as a
 * view, which charges the entries a step reads (ADR-0034), so every part ticks: a field read one tick per key of the
 * object, an index one per element stepped over; an operator, {@code and}, {@code or} and {@code //} none, and a
 * Comma's right one. No part ticks by the KiB: the interpolated value is the empty string. So a run of {@code .a} is
 * {@code 1 + n}, one whose terms are comparisons {@code 1 + 2·n}, one of variables {@code 2} whatever n, and one with a
 * generator as the top operator's right operand, or as the first term of an {@code or}, {@code 2 + 2·n}, since every
 * other term runs once for each of its two results.
 */
class WideConstructStepsTest {

  /**
   * One row per construct of ADR-0043's lowering: its name ({@link #filter}), the input, and the line's {@code a} and
   * {@code b} from 2fe9654.
   */
  static Stream<Arguments> constructs() {
    return Stream.of(
        // 2fe9654: s30 = 91, s60 = 181.
        Arguments.of("object of computed values", "{\"b\":0,\"c\":0,\"a\":0}", 1L, 3L),
        // 2fe9654: s30 = 181, s60 = 361.
        Arguments.of("object of computed keys", "{\"b\":0,\"k\":\"x\",\"a\":0}", 1L, 6L),
        // 2fe9654: s30 = 61, s60 = 121.
        Arguments.of("interpolation", "{\"b\":0,\"a\":\"\"}", 1L, 2L),
        // 2fe9654: s30 = 85, s60 = 175.
        Arguments.of("array pattern", "[0,0,0]", -5L, 3L),
        // 2fe9654: s30 = 121, s60 = 241.
        Arguments.of("object pattern of computed keys", "{\"k\":\"x\",\"b\":0}", 1L, 4L),
        // 2fe9654: s30 = 86, s60 = 176.
        Arguments.of("alternatives", "[0,0,0]", -4L, 3L),
        // 2fe9654: s30 = 61, s60 = 121.
        Arguments.of("value parameters", "{\"b\":0,\"a\":0}", 1L, 2L));
  }

  /**
   * The row's construct of {@code n} parts: an object of n constant keys with a field read each; of n pairs whose key
   * and value are field reads, the key checked; an interpolation of n field reads; an array pattern of n variables; an
   * object pattern of n computed keys, each a key step and an index step; a {@code ?//} whose first alternative is an
   * array pattern of n elements, the first {@code {$z}}, which raises on the input's 0 after every other element is
   * bound, so the second runs; and the expansion of a definition of n value parameters, each argument a field read.
   */
  static String filter(String construct, int n) {
    return switch (construct) {
      case "object of computed values" -> "{" + terms(0, n, ", ", "k%d: .a") + "}";
      case "object of computed keys" -> "{" + terms(0, n, ", ", "(.k): .a") + "}";
      case "interpolation" -> "\"" + "\\(.a)".repeat(n) + "\"";
      case "array pattern" -> ". as [" + terms(0, n, ", ", "$a%d") + "] | 1";
      case "object pattern of computed keys" -> ". as {" + terms(0, n, ", ", "(.k): $a%d") + "} | 1";
      case "alternatives" -> ". as [{$z}, " + terms(1, n, ", ", "$a%d") + "] ?// $a1 | 1";
      case "value parameters" -> "def f(" + terms(0, n, "; ", "$a%d") + "): 1; f(" + terms(0, n, "; ", ".a") + ")";
      default -> throw new IllegalArgumentException(construct);
    };
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constructs")
  void aWideConstructTicksAsTheNestedFormDoes(String construct, String input, long a, long b) {
    byte[] json = input.getBytes(UTF_8);
    for (int n : new int[] {63, 65, 20_000}) {
      String filter = filter(construct, n);
      assertThatCode(() -> Inlining.ENGINE.compile(filter)).as("compile of the %s of %d parts", construct, n).doesNotThrowAnyException();
      assertThat(fewestSteps(filter, json)).as("fewest steps of the %s of %d parts on %s, against %d + %d·%d", construct, n, input, a, b, n).isEqualTo(a + b * n);
    }
  }

  /** One run: its name, the filter of {@code n} terms, its view input, and its nested form's line {@code a + b·n}. */
  record Run(String name, String prefix, String separator, String term, String first, String last, String input, long a, long b) {
    String filter(int n) {
      StringJoiner joined = new StringJoiner(separator);
      for (int i = 0; i < n; i++) {
        joined.add(i == 0 && first != null ? first : i == n - 1 && last != null ? last : term);
      }
      return prefix + joined;
    }
  }

  /** Observed at 2fe9654: n = 30 and n = 60 give a + 30·b and a + 60·b. */
  private static final List<Run> RUNS =
      List.of(
          new Run(".a+", "", "+", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a-", "", "-", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a*", "", "*", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a/", "", "/", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a%", "", "%", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run("$x+", ".a as $x | ", "+", "$x", null, null, "{\"a\":1}", 2, 0),
          new Run(".a+, the last term (.a,.a)", "", "+", ".a", null, "(.a,.a)", "{\"a\":1}", 2, 2),
          new Run(".a and", "", " and ", ".a", null, null, "{\"a\":true}", 1, 1),
          new Run(".a or", "", " or ", ".a", null, null, "{\"a\":false}", 1, 1),
          new Run(".a or, the first term (.a,.a)", "", " or ", ".a", "(.a,.a)", null, "{\"a\":false}", 2, 2),
          new Run(".a == .a and", "", " and ", ".a == .a", null, null, "{\"a\":1}", 1, 2),
          new Run(".a //", "", " // ", ".a", null, null, "{\"a\":false}", 1, 1));

  static Stream<Arguments> runs() {
    List<Arguments> cases = new ArrayList<>();
    for (Run run : RUNS) {
      for (int n : new int[] {63, 65, 20_000}) {
        cases.add(Arguments.argumentSet(n + " terms of " + run.name() + " on " + run.input(), run, n));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("runs")
  void anOperatorRunTicksAsTheNestedFormDoes(Run run, int n) {
    String filter = run.filter(n);
    byte[] input = run.input().getBytes(UTF_8);
    assertThat(fewestSteps(filter, input)).as("fewest steps of %s on %s, its nested form's %d + %d·%d", Inlining.shown(filter), run.input(), run.a(), run.b(), n).isEqualTo(run.a() + run.b() * n);
  }

  /** The copies {@code from .. to - 1} of {@code term}, each {@code %d} in it the copy's number, joined by {@code separator}. */
  private static String terms(int from, int to, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = from; i < to; i++) {
      joined.add(term.replace("%d", Integer.toString(i)));
    }
    return joined.toString();
  }

  /** The fewest steps a run of {@code filter} on the JSON text {@code input}, read as a view, ends under, by bisection. */
  static long fewestSteps(String filter, byte[] input) {
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
}
