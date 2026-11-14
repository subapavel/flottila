package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BindsOp on a 512 KiB thread (ADR-0043, ADR-0017's default stack), compiled and run in one fresh child JVM per test
 * ({@link FreshJvm}), over a toy format whose values are integers (founding §14). Each Binds here has {@value #PARTS}
 * values and is a chain: slot k is part k's, part 0 gives 0, and part k gives slot k - 1's value plus one, by a tail
 * emitter of each kind ADR-0043 names in turn: an Intrinsic of each one-result form (Fn0, Fn1, Fn2, FnN), and a Pipe,
 * an If, a Bind and a Binds made of Identity, Const, Var and such Intrinsics. A tail value runs into a holder and the
 * loop goes on, so the whole chain runs in a stack that does not grow with it; a generator part runs the rest inside
 * its emit, one recursion per generator part, so the rest runs once per result of the generator and the parts before
 * it do not run again. A chain that recursed once per part, as the nested Bind chain does, does not fit the thread.
 *
 * <p>Each expected value is worked out by hand from the chain; the test's comment gives the steps.
 */
class BindsOpTest {
  private static final Source AT = new Source(0, 1);

  /** The values of each Binds here. */
  static final int PARTS = 20_000;

  /** The toy truth: every value but this one. */
  private static final Object NO = "no";

  private static final Truth TRUTH = v -> !NO.equals(v);

  /** The generator parts of {@link #aGeneratorPartNestsOnlyItsRest}, in their order. */
  private static final int[] GENERATORS = {1, 10_000, 19_998};

  /** The parts of {@link #aGeneratorPartNestsOnlyItsRest} that count their runs, in their order. */
  private static final int[] COUNTED = {0, 5_000, 15_000, 19_999};

  @TempDir Path dir;

  /**
   * The body gives slots 0, 9 999 and 19 999, which are 0, 9 999 and 19 999; no part ticks, and a Binds charges
   * nothing of its own (ADR-0043): 0 steps.
   */
  @Test
  void twentyThousandTailPartsRunOnA512KiBThread() throws IOException, InterruptedException {
    assertThat(FreshJvm.printed(dir, Child.class, "tail")).as("what the child JVM printed").isEqualTo("20000 tail parts: [[0, 9999, 19999]] in 0 steps\n");
  }

  /**
   * Parts 1, 10 000 and 19 998 are generators, the Comma {@code (prev + 1, prev + 100001)}, and the body gives slots
   * 1, 10 000 and 19 999. Slot 1 is 1 or 100 001; slot 9 999 is slot 1 plus 9 998, and slot 10 000 adds 1 or 100 001;
   * slot 19 997 is slot 10 000 plus 9 997, slot 19 998 adds 1 or 100 001, and slot 19 999 adds 1. The outer
   * generator's first result comes first, as in the nested chain. The Comma at part 1 runs once, at 10 000 twice and
   * at 19 998 four times, one step each: 7 steps. Part 0, before every generator, runs once; part 5 000 once per result
   * of part 1, twice; part 15 000 once per result of part 10 000 in each of those, four times; part 19 999 eight times.
   */
  @Test
  void aGeneratorPartNestsOnlyItsRest() throws IOException, InterruptedException {
    String results =
        "[[1, 10000, 19999], [1, 10000, 119999], [1, 110000, 119999], [1, 110000, 219999], "
            + "[100001, 110000, 119999], [100001, 110000, 219999], [100001, 210000, 219999], [100001, 210000, 319999]]";
    assertThat(FreshJvm.printed(dir, Child.class, "generators"))
        .as("what the child JVM printed")
        .isEqualTo("20000 parts, 3 of them generators: " + results + " in 7 steps; runs of parts 0, 5000, 15000 and 19999: [1, 2, 4, 8]\n");
  }

  /**
   * The child JVM of both tests: on a 512 KiB thread it builds the Binds of its scenario, {@code tail} or {@code
   * generators}, compiles it and runs it, and prints its results and steps, or what the thread threw.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      switch (args[0]) {
        case "tail" -> FreshJvm.print(PARTS + " tail parts", () -> ran(chain(new int[0], null, 0, 9_999)));
        case "generators" -> {
          int[] runs = new int[COUNTED.length];
          FreshJvm.print(
              PARTS + " parts, " + GENERATORS.length + " of them generators",
              () -> ran(chain(GENERATORS, runs, 1, 10_000)) + "; runs of parts 0, 5000, 15000 and 19999: " + Arrays.toString(runs));
        }
        default -> throw new IllegalArgumentException("no scenario " + args[0]);
      }
    }
  }

  /** The results of {@code root}, compiled here, with no budget and a sink that never stops, and its steps. */
  private static String ran(Binds root) {
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

  /**
   * The Binds of {@link #PARTS} parts whose body gives the slots {@code first}, {@code middle} and the last; the parts
   * in {@code generators} are generators, and when {@code runs} is not null, the parts in {@link #COUNTED} count their
   * runs in it.
   */
  private static Binds chain(int[] generators, int[] runs, int first, int middle) {
    List<Node> values = new ArrayList<>();
    List<Integer> slots = new ArrayList<>();
    int[] fresh = {PARTS};
    for (int k = 0; k < PARTS; k++) {
      values.add(part(k, generators, runs, fresh));
      slots.add(k);
    }
    return new Binds(AT, values, slots, list(var(first), var(middle), var(PARTS - 1)));
  }

  /** Part {@code k}; {@code fresh[0]} is the next slot no part took. */
  private static Node part(int k, int[] generators, int[] runs, int[] fresh) {
    if (Arrays.binarySearch(generators, k) >= 0) {
      return new Comma(AT, inc(var(k - 1)), add(var(k - 1), new Const(AT, 100_001)));
    }
    int counted = Arrays.binarySearch(COUNTED, k);
    if (runs != null && counted >= 0) {
      return counting(k, runs, counted);
    }
    if (k == 0) {
      return new Const(AT, 0);
    }
    int prev = k - 1;
    return switch (k % 9) {
      case 0 -> inc(var(prev));
      case 1 -> new Pipe(AT, var(prev), inc(new Identity(AT)));
      case 2 -> new If(AT, var(prev), inc(var(prev)), new Const(AT, NO), TRUTH);
      case 3 -> {
        int t = fresh[0]++;
        yield new Bind(AT, var(prev), t, inc(var(t)));
      }
      case 4 -> {
        int u = fresh[0];
        fresh[0] += 2;
        yield new Binds(AT, List.of(var(prev), inc(var(u))), List.of(u, u + 1), var(u + 1));
      }
      case 5 -> add(var(prev), new Const(AT, 1));
      case 6 -> call("sum", (FnN) (state, in, a) -> (Integer) a[0] + (Integer) a[1], var(prev), new Const(AT, 1));
      case 7 -> new Pipe(AT, var(prev), new Pipe(AT, new Identity(AT), call("next", (Fn0) (state, in) -> (Integer) in + 1)));
      default -> new If(AT, new Const(AT, "yes"), new Pipe(AT, var(prev), inc(new Identity(AT))), new Identity(AT), TRUTH);
    };
  }

  /** Part {@code k} as an Intrinsic that counts its runs in {@code runs[at]}: 0 for part 0, else slot k - 1's value plus one. */
  private static Node counting(int k, int[] runs, int at) {
    if (k == 0) {
      return call(
          "zero",
          (Fn0)
              (state, in) -> {
                runs[at]++;
                return 0;
              });
    }
    return call(
        "counted",
        (Fn1)
            (state, in, a) -> {
              runs[at]++;
              return (Integer) a + 1;
            },
        var(k - 1));
  }

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  private static Intrinsic call(String name, CallForm form, Node... args) {
    return new Intrinsic(AT, new Operation(name, form), List.of(args));
  }

  private static Intrinsic inc(Node arg) {
    return call("inc", (Fn1) (state, in, a) -> (Integer) a + 1, arg);
  }

  private static Intrinsic add(Node a, Node b) {
    return call("add", (Fn2) (state, in, x, y) -> (Integer) x + (Integer) y, a, b);
  }

  /** The list of its arguments. */
  private static Intrinsic list(Node... args) {
    return call("list", (FnN) (state, in, a) -> List.of(a), args);
  }
}
