package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.Specialising.DEC;
import static io.github.subapavel.jqj.query.compile.Specialising.EMPTY;
import static io.github.subapavel.jqj.query.compile.Specialising.LESS;
import static io.github.subapavel.jqj.query.compile.Specialising.PLUS;
import static io.github.subapavel.jqj.query.compile.Specialising.TAG;
import static io.github.subapavel.jqj.query.compile.Specialising.TRUTH;
import static io.github.subapavel.jqj.query.compile.Specialising.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.Specialising.N;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The specialised program runs as the original does (#117, ADR-0045 "Amendment (ticket #117)"): for each seed a random
 * program of lifted functions, PLAIN, self-recursive and mutually recursive, some closure parameters invariant and some
 * not, called from main and from each other, with arguments that read free slots, break to a Label around their call
 * and run a ParamCall of the function they are in. The original and {@code Executable.frozen().program()} both run
 * under CONSERVATIVE, which specialises nothing (ADR-0018):
 *
 * <ul>
 *   <li>the same results, error and answer;
 *   <li>the original's steps less the specialised one's are the runs of a counting op at the root of each outside
 *       argument, an argument for an invariant parameter: a replaced ParamCall has no tick, and the argument runs once
 *       for each run of the ParamCall it replaced;
 *   <li>at the least recursion-depth budget at which the original ends without a termination, the specialised program
 *       ends as it does: a replaced ParamCall counts no depth.
 * </ul>
 *
 * <p>An argument for a parameter that is not invariant has no counting op. {@link #theSeedsReachEveryFeature} shows
 * that the seeds run the counting op, break, raise, read free slots and run free ParamCalls.
 */
class SpecialiserPropertyTest {
  /** The seeds of the property. */
  static LongStream seeds() {
    return LongStream.range(0, 300);
  }

  /** {@code min(., 3)}: each countdown starts at 3 at most. */
  private static final Operation CLAMP = new Operation("clamp", (Fn0) (state, in) -> new N(Math.min(((N) in).v(), 3)));

  /** Raises {@code ["raise", .]} on 3; else gives its input. */
  private static final Operation RAISE =
      new Operation(
          "raise",
          (Fn0)
              (state, in) -> {
                if (((N) in).v() == 3) {
                  throw new FilterException(List.of("raise", in));
                }
                return in;
              });

  /** What a seed's generator put in its program, by kind: each count is of the nodes it built. */
  record Features(int freeSlots, int freeBreaks, int freeParamCalls, int recursiveGroups, int keptParameters) {}

  /**
   * One seed's program and input; {@code counts[0]} the runs of the counting op at the outside arguments' roots,
   * {@code counts[1]} the Breaks run, both added to by the program's own operations.
   */
  record Case(Program program, Object in, long[] counts, Features features) {}

  static Case generate(long seed) {
    return new Generator(seed).generate();
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aCopyRunsAsItsOriginalWithoutTheReplacedParamCalls(long seed) {
    Case c = generate(seed);
    Frozen frozen = Compiler.compile(c.program(), 0, CompileMode.OPTIMISED).frozen();
    assertThat(frozen.refused()).as("seed %d: keys refused under SPECIALISE_CAP", seed).isZero();
    Executable original = Compiler.compile(c.program(), 0, CompileMode.CONSERVATIVE);
    Executable specialised = Compiler.compile(frozen.program(), 0, CompileMode.CONSERVATIVE);

    c.counts()[0] = 0;
    Outcome o = run(original, c.in());
    long arguments = c.counts()[0];
    c.counts()[0] = 0;
    Outcome s = run(specialised, c.in());

    assertThat(s.results()).as("seed %d: the specialised results", seed).isEqualTo(o.results());
    assertThat(s.thrown()).as("seed %d: what the specialised run threw", seed).isEqualTo(o.thrown());
    assertThat(s.answer()).as("seed %d: the specialised answer", seed).isEqualTo(o.answer());
    assertThat(c.counts()[0]).as("seed %d: the outside arguments' runs in the specialised run", seed).isEqualTo(arguments);
    assertThat(o.steps() - s.steps())
        .as("seed %d: the original's steps %d less the specialised %d, against %d outside argument runs", seed, o.steps(), s.steps(), arguments)
        .isEqualTo(arguments);

    long least = leastDepth(original, c.in(), seed);
    Outcome bounded = run(specialised, c.in(), Long.MAX_VALUE, least);
    assertThat(bounded.thrown()).as("seed %d: what the specialised run threw at the original's least depth %d", seed, least).isEqualTo(o.thrown());
    assertThat(bounded.results()).as("seed %d: the specialised results at the original's least depth %d", seed, least).isEqualTo(o.results());
  }

  /** The least recursion-depth budget at which {@code executable} ends without a termination on {@code in}. */
  private static long leastDepth(Executable executable, Object in, long seed) {
    for (long depth = 0; depth <= 1_000; depth++) {
      Object thrown = run(executable, in, Long.MAX_VALUE, depth).thrown();
      if (!(thrown instanceof List<?> l && "termination".equals(l.getFirst()))) {
        return depth;
      }
    }
    throw new AssertionError("seed " + seed + ": the original needs a depth over 1000");
  }

  /**
   * The non-vacuity witness of the property, on the original programs and today's compiler: most seeds run an outside
   * argument, and enough seeds raise, break, read a free slot, run a free ParamCall, recurse and keep a parameter that
   * is not invariant.
   */
  @Test
  void theSeedsReachEveryFeature() {
    int seeds = (int) seeds().count();
    int ran = 0;
    int raised = 0;
    int broke = 0;
    int freeSlots = 0;
    int freeBreaks = 0;
    int freeParamCalls = 0;
    int recursive = 0;
    int kept = 0;
    for (long seed : seeds().toArray()) {
      Case c = generate(seed);
      Outcome o = run(Compiler.compile(c.program(), 0, CompileMode.CONSERVATIVE), c.in());
      assertThat(o.thrown()).as("seed %d: the original ends without a termination or a crash", seed).satisfiesAnyOf(t -> assertThat(t).isNull(), t -> assertThat(t).isEqualTo(List.of("error", List.of("raise", new N(3)))));
      ran += c.counts()[0] > 0 ? 1 : 0;
      raised += o.thrown() != null ? 1 : 0;
      broke += c.counts()[1] > 0 ? 1 : 0;
      freeSlots += c.features().freeSlots() > 0 ? 1 : 0;
      freeBreaks += c.features().freeBreaks() > 0 ? 1 : 0;
      freeParamCalls += c.features().freeParamCalls() > 0 ? 1 : 0;
      recursive += c.features().recursiveGroups() > 0 ? 1 : 0;
      kept += c.features().keptParameters() > 0 ? 1 : 0;
    }
    int tenth = seeds / 10;
    String all =
        "of %d seeds: %d run an argument, %d raise, %d break, %d read a free slot, %d break to a Label, %d run a free ParamCall, %d recurse, %d keep a parameter"
            .formatted(seeds, ran, raised, broke, freeSlots, freeBreaks, freeParamCalls, recursive, kept);
    assertThat(ran).as("seeds whose original runs an outside argument, %s", all).isGreaterThanOrEqualTo(seeds * 3 / 4);
    assertThat(raised).as("seeds whose original raises, %s", all).isGreaterThanOrEqualTo(tenth);
    assertThat(broke).as("seeds whose original runs a Break, %s", all).isGreaterThanOrEqualTo(tenth);
    assertThat(freeSlots).as("seeds with an argument reading a free slot, %s", all).isGreaterThanOrEqualTo(tenth);
    assertThat(freeBreaks).as("seeds with an argument breaking to a Label around its call, %s", all).isGreaterThanOrEqualTo(tenth);
    assertThat(freeParamCalls).as("seeds with an argument running a ParamCall of its function, %s", all).isGreaterThanOrEqualTo(tenth);
    assertThat(recursive).as("seeds with a recursive group, %s", all).isGreaterThanOrEqualTo(seeds / 2);
    assertThat(kept).as("seeds with a closure parameter that is not invariant, %s", all).isGreaterThanOrEqualTo(tenth);
  }

  /**
   * One seed's program: one to three groups at increasing places, each calling only the places before it from outside,
   * and a main of one to three calls, each perhaps inside a Bind its arguments read and a Label they break to.
   */
  private static final class Generator {
    private final SplittableRandom random;
    private final long[] counts = new long[2];
    private final Operation count =
        new Operation(
            "count",
            (Fn0)
                (state, in) -> {
                  counts[0]++;
                  return in;
                });
    private final Operation breaking =
        new Operation(
            "breaking",
            (Fn0)
                (state, in) -> {
                  counts[1]++;
                  return in;
                });
    private final List<Function> functions = new ArrayList<>();

    /** By function place, which closure parameters are invariant. */
    private final List<boolean[]> invariant = new ArrayList<>();

    private int at;
    private int next;
    private int freeSlots;
    private int freeBreaks;
    private int freeParamCalls;
    private int recursiveGroups;
    private int keptParameters;

    Generator(long seed) {
      random = new SplittableRandom(seed);
    }

    Case generate() {
      int groups = 1 + random.nextInt(3);
      for (int g = 0; g < groups; g++) {
        group();
      }
      next = 0;
      Node main = site();
      for (int k = random.nextInt(3); k > 0; k--) {
        main = new Comma(s(), main, site());
      }
      Features features = new Features(freeSlots, freeBreaks, freeParamCalls, recursiveGroups, keptParameters);
      return new Case(new Program(main, functions), new N(1 + random.nextInt(3)), counts, features);
    }

    private Source s() {
      return Specialising.at(at++);
    }

    /** A PLAIN function, a self-recursive one or a pair calling each other, of one or two closures and none or one value. */
    private void group() {
      int lower = functions.size();
      int closures = 1 + random.nextInt(2);
      int values = random.nextInt(2);
      int kind = random.nextInt(3);
      if (kind == 0) {
        boolean[] all = new boolean[closures];
        Arrays.fill(all, true);
        invariant.add(all);
        functions.add(new Function(s(), closures, values, plain(closures, values, lower)));
        return;
      }
      recursiveGroups++;
      boolean[] inv = new boolean[closures];
      for (int j = 0; j < closures; j++) {
        inv[j] = random.nextInt(3) != 0;
        keptParameters += inv[j] ? 0 : 1;
      }
      if (kind == 1) {
        invariant.add(inv);
        functions.add(new Function(s(), closures, values, recursive(closures, values, lower, lower, inv)));
      } else {
        invariant.add(inv);
        invariant.add(inv);
        Function a = new Function(s(), closures, values, recursive(closures, values, lower, lower + 1, inv));
        functions.add(a);
        functions.add(new Function(s(), closures, values, recursive(closures, values, lower, lower, inv)));
      }
    }

    private Node plain(int closures, int values, int lower) {
      next = values;
      return use(closures, values, lower, 2);
    }

    /**
     * {@code clamp | if . then use, (dec | partner(...)) else leaf end}: the partner gets each invariant parameter as a
     * bare ParamCall of itself, each other as {@code ParamCall(k) | tag(.)}, and the value as it is or the input.
     */
    private Node recursive(int closures, int values, int lower, int partner, boolean[] inv) {
      next = values;
      Node use = use(closures, values, lower, 2);
      List<Node> pass = new ArrayList<>();
      for (int j = 0; j < closures; j++) {
        pass.add(inv[j] ? new ParamCall(s(), j) : new Pipe(s(), new ParamCall(s(), random.nextInt(closures)), tag()));
      }
      List<Node> value = new ArrayList<>();
      if (values > 0) {
        value.add(random.nextBoolean() ? new Var(s(), 0) : new Identity(s()));
      }
      Node again = new Pipe(s(), new Intrinsic(s(), DEC, List.of()), new Call(s(), partner, pass, value));
      Node loop = new If(s(), new Identity(s()), new Comma(s(), use, again), leaf(), TRUTH);
      return new Pipe(s(), new Intrinsic(s(), CLAMP, List.of()), loop);
    }

    /** What a function does with its closures, its value and the functions before {@code lower}. */
    private Node use(int closures, int values, int lower, int depth) {
      int pick = random.nextInt(depth > 0 ? 8 : 3);
      return switch (pick) {
        case 0 -> paramCall(closures);
        case 1 -> values > 0 ? new Pipe(s(), paramCall(closures), plus(new Var(s(), 0))) : paramCall(closures);
        case 2 -> new Pipe(s(), new Intrinsic(s(), RAISE, List.of()), paramCall(closures));
        case 3 -> new Comma(s(), use(closures, values, lower, depth - 1), use(closures, values, lower, depth - 1));
        case 4 -> new Pipe(s(), use(closures, values, lower, depth - 1), use(closures, values, lower, depth - 1));
        default -> lower > 0 ? call(random.nextInt(lower), closures, values, -1, -1) : paramCall(closures);
      };
    }

    private Node paramCall(int closures) {
      return new ParamCall(s(), random.nextInt(closures));
    }

    private Node tag() {
      return new Intrinsic(s(), TAG, List.of(new Identity(s())));
    }

    private Node plus(Node atom) {
      return new Intrinsic(s(), PLUS, List.of(new Identity(s()), atom));
    }

    private Node leaf() {
      return switch (random.nextInt(3)) {
        case 0 -> new Identity(s());
        case 1 -> new Const(s(), new N(random.nextInt(3)));
        default -> new Intrinsic(s(), EMPTY, List.of());
      };
    }

    /**
     * An outside call of {@code fn} from a body of {@code closures} closures and {@code values} values (both 0 in
     * main), where {@code slot} is a free slot its arguments may read and {@code label} a Label they may break to, or
     * -1. In a function an argument for an invariant parameter is now and then a bare ParamCall of its own, passed on.
     * Never one for a parameter that is not invariant: the argument passed on would then run through that callee's
     * ParamCall, which stays, and the steps would not show its runs.
     */
    private Node call(int fn, int closures, int values, int slot, int label) {
      Function f = functions.get(fn);
      List<Node> args = new ArrayList<>();
      for (int k = 0; k < f.closures(); k++) {
        if (closures > 0 && invariant.get(fn)[k] && random.nextInt(5) == 0) {
          args.add(paramCall(closures));
        } else {
          Node inner = inner(closures, values, slot, label);
          args.add(invariant.get(fn)[k] ? new Pipe(s(), new Intrinsic(s(), count, List.of()), inner) : inner);
        }
      }
      List<Node> value = new ArrayList<>();
      for (int k = 0; k < f.values(); k++) {
        value.add(
            switch (random.nextInt(3)) {
              case 0 -> new Identity(s());
              case 1 -> new Const(s(), new N(random.nextInt(3)));
              default ->
                  values > 0 ? new Var(s(), 0) : slot >= 0 ? new Var(s(), slot) : new Identity(s());
            });
      }
      return new Call(s(), fn, args, value);
    }

    /** The body of an argument: closed, or reading a free slot, breaking out or running a ParamCall of its function. */
    private Node inner(int closures, int values, int slot, int label) {
      int pick = label >= 0 && random.nextBoolean() ? 4 : random.nextInt(7);
      if (pick == 2 && (slot >= 0 || values > 0)) {
        freeSlots++;
        return plus(new Var(s(), slot >= 0 ? slot : 0));
      }
      if (pick == 3 && (slot >= 0 || values > 0)) {
        freeSlots++;
        int own = next++;
        Node read = new Intrinsic(s(), PLUS, List.of(new Var(s(), own), new Var(s(), slot >= 0 ? slot : 0)));
        return new Bind(s(), new Identity(s()), own, read);
      }
      if (pick == 4 && label >= 0) {
        freeBreaks++;
        Node small = new Intrinsic(s(), LESS, List.of(new Identity(s()), new Const(s(), new N(2))));
        Node out = new Pipe(s(), new Intrinsic(s(), breaking, List.of()), new Break(s(), label));
        return new If(s(), small, out, plus(new Const(s(), new N(1))), TRUTH);
      }
      if (pick >= 5 && closures > 0) {
        freeParamCalls++;
        return new Pipe(s(), paramCall(closures), tag());
      }
      if (pick == 1) {
        int own = next++;
        return new Bind(s(), new Identity(s()), own, new Intrinsic(s(), PLUS, List.of(new Var(s(), own), new Const(s(), new N(0)))));
      }
      return pick == 0 ? tag() : plus(new Const(s(), new N(1)));
    }

    /** One call of main, of one of the last two places, perhaps inside {@code k as $slot} and a Label. */
    private Node site() {
      int top = functions.size();
      int fn = top - 1 - random.nextInt(Math.min(top, 2));
      int slot = random.nextBoolean() ? next++ : -1;
      int label = random.nextBoolean() ? next++ : -1;
      Node call = call(fn, 0, 0, slot, label);
      if (label >= 0) {
        call = new Label(s(), label, call);
      }
      if (slot >= 0) {
        call = new Bind(s(), new Const(s(), new N(random.nextInt(3))), slot, call);
      }
      return call;
    }
  }
}
