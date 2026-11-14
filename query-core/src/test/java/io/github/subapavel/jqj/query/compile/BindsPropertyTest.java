package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.RandomParts.ROOT;
import static io.github.subapavel.jqj.query.compile.RandomParts.outcome;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.RandomParts.Both;
import io.github.subapavel.jqj.query.compile.RandomParts.Fence;
import io.github.subapavel.jqj.query.compile.RandomParts.Form;
import io.github.subapavel.jqj.query.compile.RandomParts.Generator;
import io.github.subapavel.jqj.query.compile.RandomParts.Let;
import io.github.subapavel.jqj.query.compile.RandomParts.Lets;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.RandomParts.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Seeded property of ADR-0043 (docs/agents/environment.md, one case per seed): {@code Binds(source, values, slots,
 * body)} means {@code Bind(v1, s1, … Bind(vn, sn, body))}, the first outermost, so a Binds runs as that nested chain
 * does: the same results in the same order, the same error or termination, the same answer to a sink that stops, and
 * the same steps and bytes built, in both compile modes. Binds ticks and charges nothing of its own, and a tail value
 * taken into a holder before the rest runs changes none of it.
 *
 * <p>Each seed builds one root, {@code label $root | B} or {@code label $root | (c1, c2) as $o | B}, B a Binds of two
 * to six values. Each value is a random part of {@link RandomParts}: a tail emitter, a Comma, a raising part, an empty
 * part, a Many or a Collect whose body breaks (to the root Label or to a Label inside it), or a compound of them,
 * reading the slots before it; the body runs such a part and then gives every slot. The oracle is the same tree in the
 * push form of {@link RandomParts}: every Binds, the root's and those inside its parts, as nested Bind nodes. The seed
 * also picks the step budget (a small one in a third of the seeds) and the result at which the sink stops (none in a
 * third). The seed is the case's name and is in every description.
 */
class BindsPropertyTest {

  static LongStream seeds() {
    return LongStream.range(0, 400);
  }

  /** The root of one seed, the first slot its parts left free, its step budget and the result its sink stops at. */
  record Case(Part root, int free, long steps, int stopAt) {}

  static Case generate(long seed) {
    Generator g = new Generator(seed);
    boolean twice = g.below(2) == 0;
    int outer = twice ? g.fresh() : -1;
    List<Integer> scope = twice ? List.of(outer) : List.of();
    Lets binds = g.top(scope, 2, 6);
    Part main = twice ? new Let(new Both(g.constant(), g.constant()), outer, binds) : binds;
    long steps = g.below(3) == 0 ? g.below(12) : Long.MAX_VALUE;
    int stopAt = g.below(3) == 0 ? Integer.MAX_VALUE : 1 + g.below(8);
    return new Case(new Fence(ROOT, main), g.free(), steps, stopAt);
  }

  /** The outcome of the Binds of {@code c} in {@code mode}. */
  private static Outcome binds(Case c, CompileMode mode) {
    return outcome(c.root().build(Form.tail(c.free())), mode, c.steps(), c.stopAt());
  }

  /** The outcome of the nested chain of {@code c} in {@code mode}: the oracle. */
  private static Outcome nested(Case c, CompileMode mode) {
    return outcome(c.root().build(Form.push(c.free())), mode, c.steps(), c.stopAt());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aBindsRunsAsItsNestedBindsDo(long seed) {
    Case c = generate(seed);
    for (CompileMode mode : CompileMode.values()) {
      Outcome nested = nested(c, mode);
      assertThat(nested.crashed()).as("seed %d, %s: whether the nested chain crashed (%s) on %s", seed, mode, nested, c).isFalse();
      assertThat(binds(c, mode)).as("seed %d, %s: outcome of the Binds of %s", seed, mode, c).isEqualTo(nested);
    }
  }

  /**
   * Witness that the property is not vacuous: over the seeds, the Binds form runs out with two results or more, runs
   * out with none, stops at the sink, raises and ends at the step budget, each in 5 % of the seeds at least, and no seed
   * crashes. (At #92's red the nested form of the 400 seeds gave 45, 116, 47, 140 and 45, and 158 of them reached the
   * body with every slot set.)
   */
  @Test
  void theSeedsReachEveryOutcome() {
    List<Outcome> outcomes = new ArrayList<>();
    seeds().forEach(seed -> outcomes.add(binds(generate(seed), CompileMode.OPTIMISED)));
    Map<String, Integer> kinds = RandomParts.kinds(outcomes);
    int least = outcomes.size() / 20;
    assertThat(kinds).as("outcomes of the Binds form by kind over %d seeds", outcomes.size()).doesNotContainKey("crash");
    for (String kind : List.of("results", "none", "stop", "error", "termination")) {
      assertThat(kinds.getOrDefault(kind, 0)).as("seeds whose Binds form ends in %s, of %s", kind, kinds).isGreaterThanOrEqualTo(least);
    }
  }
}
