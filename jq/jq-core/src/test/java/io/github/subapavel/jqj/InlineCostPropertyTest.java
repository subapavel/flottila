package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.JqTermination.Cause;
import io.github.subapavel.jqj.value.JqNull;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.LongFunction;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Inlining costs nothing (ADR-0040 "Cost": an inlined call, closure or value parameter ticks nothing and builds
 * nothing; GLOSSARY "Closure"): for a random body B with holes and a random closure A, {@code def f(g): B; f(A)} gives
 * what B with each hole replaced by {@code (A)} gives, in results and error, in steps and in bytes built. Both sides
 * are jqj's, so the expected side is the one without a definition; the constructs are those built before #70 (#63,
 * #64), and the input is null. A body may have no hole, so A is never run. Steps and bytes built are the least budget
 * a run completes under, an error included; {@code 1, (…)} and {@code [0], (…)} make both at least one, since a budget
 * is never 0 (ADR-0024).
 */
class InlineCostPropertyTest {

  /** Where B takes the closure. */
  private static final String HOLE = "%G%";

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aClosureCostsWhatItsTextCosts(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    String body = template(random, 3, true);
    String closure = template(random, 2, false);
    String defined = "def f(g): " + body.replace(HOLE, "g") + "; f(" + closure + ")";
    String substituted = body.replace(HOLE, "(" + closure + ")");
    String what = String.format("seed %d: %s against %s", seed, defined, substituted);

    assertThat(outcome(defined)).as("results of %s", what).isEqualTo(outcome(substituted));
    assertThat(least(RunBudgets::steps, "1, (" + defined + ")"))
        .as("steps of %s", what)
        .isEqualTo(least(RunBudgets::steps, "1, (" + substituted + ")"));
    assertThat(least(RunBudgets::bytesBuilt, "[0], (" + defined + ")"))
        .as("bytes built of %s", what)
        .isEqualTo(least(RunBudgets::bytesBuilt, "[0], (" + substituted + ")"));
  }

  /** A filter of the constructs built before #70, {@code depth} levels at most, with holes when {@code holes}. */
  private static String template(RandomGenerator random, int depth, boolean holes) {
    if (depth == 0 || random.nextInt(3) == 0) {
      return switch (random.nextInt(holes ? 10 : 8)) {
        case 0 -> ".";
        case 1 -> "1";
        case 2 -> "\"s\"";
        case 3 -> "null";
        case 4 -> "empty";
        case 5 -> ".a";
        case 6 -> ".[]?";
        case 7 -> ".[]";
        default -> HOLE;
      };
    }
    String left = template(random, depth - 1, holes);
    return switch (random.nextInt(5)) {
      case 0 -> "(" + left + " , " + template(random, depth - 1, holes) + ")";
      case 1 -> "(" + left + " | " + template(random, depth - 1, holes) + ")";
      case 2 -> "[" + left + "]";
      case 3 -> "{a: " + left + "}";
      default -> "{a: " + left + ", b: " + template(random, depth - 1, holes) + "}";
    };
  }

  /** The results of {@code filter} on null as compact JSON, then {@code error <value>} if the run raised. */
  private static List<String> outcome(String filter) {
    JqEngine engine = RunBudgets.unlimited();
    List<String> outcome = new ArrayList<>();
    try {
      engine
          .compile(filter)
          .run(
              JqNull.NULL,
              NONE,
              v -> {
                outcome.add(v.toJson());
                return true;
              },
              engine.newWorkspace());
    } catch (JqFilterException e) {
      outcome.add("error " + e.value().toJson());
    }
    return outcome;
  }

  /** The least budget of {@code budget}'s engines under which a run of {@code filter} on null completes. */
  private static long least(LongFunction<JqEngine> budget, String filter) {
    long low = 1;
    long high = 1;
    while (!completes(budget.apply(high), filter)) {
      assertThat(high).as("budget of %s", filter).isLessThan(1L << 30);
      low = high + 1;
      high *= 2;
    }
    while (low < high) {
      long mid = (low + high) >>> 1;
      if (completes(budget.apply(mid), filter)) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return high;
  }

  /** Whether a run of {@code filter} under {@code engine} ends without a budget's termination; an error completes it. */
  private static boolean completes(JqEngine engine, String filter) {
    try {
      engine.compile(filter).run(JqNull.NULL, NONE, v -> true, engine.newWorkspace());
      return true;
    } catch (JqFilterException e) {
      return true;
    } catch (JqTermination t) {
      assertThat(t.cause()).as("cause of the termination of %s", filter).isEqualTo(Cause.BUDGET);
      assertThat(t.budget()).as("budget of the termination of %s", filter).isIn(Budget.STEPS, Budget.BYTES_BUILT);
      return false;
    }
  }
}
