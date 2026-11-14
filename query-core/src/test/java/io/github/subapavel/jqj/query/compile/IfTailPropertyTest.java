package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.RandomParts.NO;
import static io.github.subapavel.jqj.query.compile.RandomParts.ROOT;
import static io.github.subapavel.jqj.query.compile.RandomParts.TRUTH;
import static io.github.subapavel.jqj.query.compile.RandomParts.outcome;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.RandomParts.Both;
import io.github.subapavel.jqj.query.compile.RandomParts.Con;
import io.github.subapavel.jqj.query.compile.RandomParts.Cond;
import io.github.subapavel.jqj.query.compile.RandomParts.Fence;
import io.github.subapavel.jqj.query.compile.RandomParts.Form;
import io.github.subapavel.jqj.query.compile.RandomParts.Generator;
import io.github.subapavel.jqj.query.compile.RandomParts.Let;
import io.github.subapavel.jqj.query.compile.RandomParts.Nothing;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.RandomParts.Over;
import io.github.subapavel.jqj.query.compile.RandomParts.Part;
import io.github.subapavel.jqj.query.compile.RandomParts.Raise;
import io.github.subapavel.jqj.query.compile.RandomParts.Tag;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * IfOp takes a tail condition's result before it runs the branch (ADR-0043): a tail emitter gives one result at most
 * and never breaks out, so the branch may run after the condition returned instead of inside its emit. That changes
 * how deep the run goes and nothing else: the same results in the same order, the same error or termination, the same
 * answer to a sink that stops, and the same steps and bytes built.
 *
 * <p>The seeded property (docs/agents/environment.md, one case per seed) builds {@code label $root | (c1, c2) as $o |
 * if C then T else E end}, C a random tail emitter of {@link RandomParts}, a compound one in three seeds of four, and T
 * and E random parts of any kind (a Comma, a raising or an empty part, a Many, a Collect whose body breaks, or a
 * compound). Its push form, the oracle, is the same tree with each If's condition inside a Label no Break names and
 * each Binds as nested Bind nodes ({@link RandomParts}). The seed also picks the step budget and the result at which
 * the sink stops, and is the case's name and in every description.
 *
 * <p>The depth: a run of {@code and} or {@code or} nests in an If's condition (ADR-0037's lowering, ADR-0043 builds a
 * long run balanced), and in push form each term's emit stays on the stack while every later term runs. Taken first,
 * the balanced run of {@value #TERMS} terms runs on a 512 KiB thread in a fresh child JVM ({@link FreshJvm}).
 */
class IfTailPropertyTest {
  private static final Source AT = new Source(0, 1);

  /** The terms of each balanced run. */
  static final int TERMS = 20_000;

  /** The toy {@code true}; the toy {@code false} is {@link RandomParts#NO}. */
  private static final Object YES = "yes";

  @TempDir Path dir;

  static LongStream seeds() {
    return LongStream.range(0, 400);
  }

  /** The root of one seed, the first slot its parts left free, its step budget and the result its sink stops at. */
  record Case(Part root, int free, long steps, int stopAt) {}

  static Case generate(long seed) {
    Generator g = new Generator(seed);
    int outer = g.fresh();
    List<Integer> scope = List.of(outer);
    Part cond = g.below(4) == 0 ? g.tail(3, scope) : g.compound(3, scope);
    Part main = new Let(new Both(g.constant(), g.constant()), outer, new Cond(cond, g.part(scope), g.part(scope)));
    long steps = g.below(3) == 0 ? g.below(8) : Long.MAX_VALUE;
    int stopAt = g.below(3) == 0 ? Integer.MAX_VALUE : 1 + g.below(6);
    return new Case(new Fence(ROOT, main), g.free(), steps, stopAt);
  }

  /** The outcome of {@code c} with its If on its tail condition, in {@code mode}. */
  private static Outcome tail(Case c, CompileMode mode) {
    return outcome(c.root().build(Form.tail(c.free())), mode, c.steps(), c.stopAt());
  }

  /** The outcome of the push form of {@code c} in {@code mode}: the oracle. */
  private static Outcome push(Case c, CompileMode mode) {
    return outcome(c.root().build(Form.push(c.free())), mode, c.steps(), c.stopAt());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aTailConditionRunsAsItsPushFormDoes(long seed) {
    Case c = generate(seed);
    for (CompileMode mode : CompileMode.values()) {
      Outcome push = push(c, mode);
      assertThat(push.crashed()).as("seed %d, %s: whether the push form crashed (%s) on %s", seed, mode, push, c).isFalse();
      assertThat(tail(c, mode)).as("seed %d, %s: outcome of the If on its tail condition in %s", seed, mode, c).isEqualTo(push);
    }
  }

  /**
   * Witness that the property is not vacuous: over the seeds, the If on its tail condition runs out with two results
   * or more, runs out with none, stops at the sink, raises and ends at the step budget, each in 5 % of the seeds at
   * least, and no seed crashes. (At #92's red the push form of the 400 seeds gave 150, 65, 80, 66 and 36.)
   */
  @Test
  void theSeedsReachEveryOutcome() {
    List<Outcome> outcomes = new ArrayList<>();
    seeds().forEach(seed -> outcomes.add(tail(generate(seed), CompileMode.OPTIMISED)));
    Map<String, Integer> kinds = RandomParts.kinds(outcomes);
    int least = outcomes.size() / 20;
    assertThat(kinds).as("outcomes of the tail form by kind over %d seeds", outcomes.size()).doesNotContainKey("crash");
    for (String kind : List.of("results", "none", "stop", "error", "termination")) {
      assertThat(kinds.getOrDefault(kind, 0)).as("seeds whose tail form ends in %s, of %s", kind, kinds).isGreaterThanOrEqualTo(least);
    }
  }

  /** The toy null: false to {@link #AND_OR} as {@link RandomParts#NO} is. */
  private static final Object NIL = "nil";

  /** The truth of the and/or property: every value but {@link RandomParts#NO} and {@link #NIL} holds. */
  private static final Truth AND_OR = v -> !NO.equals(v) && !NIL.equals(v);

  /**
   * One and/or run of one seed: its terms, whether they are joined by {@code and} (else {@code or}), whether a balanced
   * half of an odd number of terms gives its middle term to the left half, its step budget and the result its sink
   * stops at.
   */
  record AndOr(List<Part> terms, boolean and, boolean leftTakesTheMiddle, long steps, int stopAt) {}

  /**
   * Two to 40 random terms: true (a constant or a one-result call giving a list), false, the toy null, raising, or
   * generating (a Comma of two of the three truth values, a Many of zero to three of them that may raise after its
   * last, or a call with no result). Only a generating term ticks, and a run often ends before it reaches one, so two
   * seeds in three get a step budget of zero to three steps: with one in three of zero to seven, only 11 of the 400
   * left-nested chains ended on it, under {@link #theAndOrSeedsReachEveryOutcome}'s 5 %.
   */
  static AndOr andOr(long seed) {
    Generator g = new Generator(seed);
    List<Part> terms = new ArrayList<>();
    for (int k = 2 + g.below(39); k > 0; k--) {
      int pick = g.below(20);
      if (pick < 3) {
        terms.add(new Con(YES));
      } else if (pick < 5) {
        terms.add(new Tag("t" + g.below(3), -1));
      } else if (pick < 9) {
        terms.add(new Con(NO));
      } else if (pick < 12) {
        terms.add(new Con(NIL));
      } else if (pick < 14) {
        terms.add(new Raise("e" + g.below(3), -1));
      } else if (pick < 16) {
        terms.add(new Both(new Con(truth(g)), new Con(truth(g))));
      } else if (pick < 19) {
        List<Object> values = new ArrayList<>();
        for (int v = g.below(4); v > 0; v--) {
          values.add(truth(g));
        }
        terms.add(new Over(List.copyOf(values), g.below(4) == 0));
      } else {
        terms.add(new Nothing());
      }
    }
    boolean and = g.below(2) == 0;
    boolean leftTakesTheMiddle = g.below(2) == 0;
    long steps = g.below(3) == 0 ? Long.MAX_VALUE : g.below(4);
    int stopAt = g.below(3) == 0 ? Integer.MAX_VALUE : 1 + g.below(6);
    return new AndOr(List.copyOf(terms), and, leftTakesTheMiddle, steps, stopAt);
  }

  private static Object truth(Generator g) {
    return switch (g.below(3)) {
      case 0 -> YES;
      case 1 -> NO;
      default -> NIL;
    };
  }

  /** {@code l and r} or {@code l or r} as ADR-0037 lowers it, on {@link #AND_OR}. */
  private static Node joined(boolean and, Node l, Node r) {
    Node bool = new If(AT, r, new Const(AT, YES), new Const(AT, NO), AND_OR);
    return and ? new If(AT, l, bool, new Const(AT, NO), AND_OR) : new If(AT, l, new Const(AT, YES), bool, AND_OR);
  }

  /** The left-nested chain of the parser's left-associative run: {@code ((t0 op t1) op t2) op …}. */
  static Node chain(AndOr run) {
    Form form = Form.tail(ROOT + 1);
    Node chain = run.terms().getFirst().build(form);
    for (Part term : run.terms().subList(1, run.terms().size())) {
      chain = joined(run.and(), chain, term.build(form));
    }
    return chain;
  }

  /** The terms {@code [from, to)} of {@code run} joined balanced, each half lowered as one operand (ADR-0043). */
  static Node balanced(AndOr run, int from, int to) {
    if (to - from == 1) {
      return run.terms().get(from).build(Form.tail(ROOT + 1));
    }
    int middle = run.leftTakesTheMiddle() ? from + (to - from + 1) / 2 : (from + to) >>> 1;
    return joined(run.and(), balanced(run, from, middle), balanced(run, middle, to));
  }

  /**
   * ADR-0043: a run of {@code and} or {@code or} built balanced from ADR-0037's lowering runs as the parser's
   * left-nested chain does, both being associative under it: the same results in the same order, the same error or
   * termination, the same answer to a sink that stops, and the same steps and bytes built, in both compile modes. Both
   * trees are built here; the seed picks the terms, the operator, the split of an odd half, the step budget and the
   * result at which the sink stops.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aBalancedAndOrTreeRunsAsTheLeftNestedChain(long seed) {
    AndOr run = andOr(seed);
    for (CompileMode mode : CompileMode.values()) {
      Outcome chain = outcome(chain(run), mode, run.steps(), run.stopAt());
      assertThat(chain.crashed()).as("seed %d, %s: whether the left-nested chain crashed (%s) on %s", seed, mode, chain, run).isFalse();
      Outcome balanced = outcome(balanced(run, 0, run.terms().size()), mode, run.steps(), run.stopAt());
      assertThat(balanced).as("seed %d, %s: outcome of the balanced tree of %s", seed, mode, run).isEqualTo(chain);
    }
  }

  /** The right-nested Alt chain of the parser's right-associative {@code //} run: {@code t0 // (t1 // (t2 // …))}. */
  static Node alternatives(AndOr run) {
    Form form = Form.tail(ROOT + 1);
    Node chain = run.terms().getLast().build(form);
    for (int i = run.terms().size() - 2; i >= 0; i--) {
      chain = new Alt(AT, run.terms().get(i).build(form), chain, AND_OR);
    }
    return chain;
  }

  /** The terms {@code [from, to)} of {@code run} joined balanced by Alt, each half one operand (#94). */
  static Node balancedAlternatives(AndOr run, int from, int to) {
    if (to - from == 1) {
      return run.terms().get(from).build(Form.tail(ROOT + 1));
    }
    int middle = run.leftTakesTheMiddle() ? from + (to - from + 1) / 2 : (from + to) >>> 1;
    return new Alt(AT, balancedAlternatives(run, from, middle), balancedAlternatives(run, middle, to), AND_OR);
  }

  /**
   * #94 (ADR-0043 "// runs"): a {@code //} run built as a balanced Alt tree runs as the parser's right-nested chain
   * does, Alt being associative (it emits each truthy left result, runs its right side only if there was none and
   * catches nothing, ADR-0037): the same results in the same order, the same error or termination, the same answer to
   * a sink that stops, and the same steps and bytes built, in both compile modes. The seeds' terms are the and/or
   * property's ({@link #andOr}), which ignores its operator here.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aBalancedAlternativeTreeRunsAsTheRightNestedChain(long seed) {
    AndOr run = andOr(seed);
    for (CompileMode mode : CompileMode.values()) {
      Outcome chain = outcome(alternatives(run), mode, run.steps(), run.stopAt());
      assertThat(chain.crashed()).as("seed %d, %s: whether the right-nested chain crashed (%s) on %s", seed, mode, chain, run).isFalse();
      Outcome balanced = outcome(balancedAlternatives(run, 0, run.terms().size()), mode, run.steps(), run.stopAt());
      assertThat(balanced).as("seed %d, %s: outcome of the balanced Alt tree of %s", seed, mode, run).isEqualTo(chain);
    }
  }

  /**
   * Witness that the and/or property is not vacuous: over the seeds, the left-nested chain runs out with two results or
   * more, with one, with none, stops at the sink, raises and ends at the step budget, each in 5 % of the seeds at least,
   * and gives both truth values among its results; no seed crashes. (At #94's red the chains of the 400 seeds gave 38,
   * 171, 42, 34, 81 and 34.)
   */
  @Test
  void theAndOrSeedsReachEveryOutcome() {
    List<Outcome> outcomes = new ArrayList<>();
    seeds().forEach(seed -> outcomes.add(outcome(chain(andOr(seed)), CompileMode.OPTIMISED, andOr(seed).steps(), andOr(seed).stopAt())));
    Map<String, Integer> kinds = RandomParts.kinds(outcomes);
    int least = outcomes.size() / 20;
    assertThat(kinds).as("outcomes of the chains by kind over %d seeds", outcomes.size()).doesNotContainKey("crash");
    for (String kind : List.of("results", "one", "none", "stop", "error", "termination")) {
      assertThat(kinds.getOrDefault(kind, 0)).as("seeds whose chain ends in %s, of %s", kind, kinds).isGreaterThanOrEqualTo(least);
    }
    assertThat(outcomes.stream().flatMap(o -> o.results().stream()).distinct()).as("the results of every chain").containsExactlyInAnyOrder(YES, NO);
  }

  /**
   * {@code a and b} is {@code if a then (if b then true else false) else false} and {@code a or b} is {@code if a then
   * true else (if b then true else false)} (ADR-0037), here over balanced halves. Every term true gives true, the last
   * term false gives false, and every term false gives false for {@code or}; none of it ticks.
   */
  @Test
  void aBalancedRunOfTailConditionsRunsOnA512KiBThread() throws IOException, InterruptedException {
    assertThat(FreshJvm.printed(dir, Child.class, "balanced"))
        .as("what the child JVM printed")
        .isEqualTo(
            "20000 and-terms: [yes] in 0 steps\n"
                + "20000 and-terms, the last one false: [no] in 0 steps\n"
                + "20000 or-terms, all false: [no] in 0 steps\n");
  }

  /** The child JVM of {@link #aBalancedRunOfTailConditionsRunsOnA512KiBThread}: each run on a 512 KiB thread. */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      FreshJvm.print(TERMS + " and-terms", () -> ran(and(0, TERMS, -1)));
      FreshJvm.print(TERMS + " and-terms, the last one false", () -> ran(and(0, TERMS, TERMS - 1)));
      FreshJvm.print(TERMS + " or-terms, all false", () -> ran(or(0, TERMS)));
    }
  }

  /**
   * The results of {@code run}, compiled here, inside {@code yes as $0 | no as $1 | …}, with no budget and a sink that
   * never stops, and its steps.
   */
  private static String ran(Node run) {
    Node root = new Bind(AT, new Const(AT, YES), 0, new Bind(AT, new Const(AT, NO), 1, run));
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    List<Object> results = new ArrayList<>();
    boolean ranOut =
        Compiler.compile(root, CompileMode.OPTIMISED)
            .run(
                state,
                "in",
                v -> {
                  results.add(v);
                  return true;
                });
    return (ranOut ? "" : "stopped: ") + results + " in " + state.steps() + " steps";
  }

  /** The terms {@code [from, to)} joined by {@code and}, balanced; term {@code no} is false, every other one true. */
  private static Node and(int from, int to, int no) {
    if (to - from == 1) {
      return term(from, from == no ? NO : YES);
    }
    int middle = (from + to) >>> 1;
    return new If(AT, and(from, middle, no), new If(AT, and(middle, to, no), new Const(AT, YES), new Const(AT, NO), TRUTH), new Const(AT, NO), TRUTH);
  }

  /** The terms {@code [from, to)}, every one false, joined by {@code or}, balanced. */
  private static Node or(int from, int to) {
    if (to - from == 1) {
      return term(from, NO);
    }
    int middle = (from + to) >>> 1;
    return new If(AT, or(from, middle), new Const(AT, YES), new If(AT, or(middle, to), new Const(AT, YES), new Const(AT, NO), TRUTH), TRUTH);
  }

  /** Term {@code k} giving {@code value}: a Const, a Var of slot 0 or 1 (yes or no), or an Fn0, by k mod 3. */
  private static Node term(int k, Object value) {
    return switch (k % 3) {
      case 0 -> new Const(AT, value);
      case 1 -> new Var(AT, YES.equals(value) ? 0 : 1);
      default -> new Intrinsic(AT, new Operation("term", (Fn0) (state, in) -> value), List.of());
    };
  }
}
