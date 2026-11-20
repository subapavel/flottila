package dev.rail8.jq.query.compile;

import static dev.rail8.jq.query.compile.RandomParts.AT;
import static dev.rail8.jq.query.compile.RandomParts.IN;
import static dev.rail8.jq.query.compile.RandomParts.NO;
import static dev.rail8.jq.query.compile.RandomParts.TRUTH;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.compile.BindsPropertyTest.Case;
import dev.rail8.jq.query.compile.RandomParts.Form;
import dev.rail8.jq.query.compile.RandomParts.Outcome;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Collect;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Facts;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Tracking;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Termination;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Seeded property: the path variant of a tree runs as its {@code run} does. Each seed takes the root of
 * {@link BindsPropertyTest}'s seed, sets the tracking of each of its Bind and Binds values to VALUE or PATH and the path
 * rule of each of its operations to NONE or SAME at random, and puts it in the else branch of an If whose condition is false and whose then branch is a PathOf, so the program
 * compiles as one that holds paths. The parts are the toy ones of {@link RandomParts}, none of whose
 * operations is a step, so no path variant raises where {@code run} does not. Over the main tree's path
 * variant, in both compile modes, in push form and on the trampoline (a declared stack of 0), the results, their order,
 * the answer to a sink that stops, the error or termination, the steps and the bytes built must be {@code run}'s, and
 * both forms of the path variant must give each result the same path.
 */
class PathVariantPropertyTest {
  private static final long UNLIMITED = Long.MAX_VALUE;

  static LongStream seeds() {
    return BindsPropertyTest.seeds();
  }

  /** The tree of {@code seed}: {@link BindsPropertyTest}'s root, tracked at random, in the else branch. */
  private static Node root(Case c, long seed) {
    Node root = track(c.root().build(Form.tail(c.free())), new SplittableRandom(seed));
    return new If(AT, new Const(AT, NO), new PathOf(AT, new Identity(AT), new ListPaths()), root, TRUTH);
  }

  /**
   * {@code node} with each Bind and Binds value tracked as VALUE or PATH and each operation's results untracked or at
   * its input's path, at random; every other node as it is.
   */
  private static Node track(Node node, SplittableRandom random) {
    return switch (node) {
      case Bind b -> new Bind(b.source(), track(b.value(), random), tracking(random), b.slot(), track(b.body(), random));
      case Binds b -> {
        List<Node> values = new ArrayList<>();
        List<Tracking> trackings = new ArrayList<>();
        for (Node value : b.values()) {
          values.add(track(value, random));
          trackings.add(tracking(random));
        }
        yield new Binds(b.source(), values, trackings, b.slots(), track(b.body(), random));
      }
      case Pipe p -> new Pipe(p.source(), track(p.left(), random), track(p.right(), random));
      case Comma c -> new Comma(c.source(), track(c.left(), random), track(c.right(), random));
      case If i -> new If(i.source(), track(i.cond(), random), track(i.then(), random), track(i.otherwise(), random), i.truth());
      case Collect c -> new Collect(c.source(), track(c.body(), random), c.collector());
      case Label l -> new Label(l.source(), l.slot(), track(l.body(), random));
      case Intrinsic i -> {
        Operation op = i.op();
        yield new Intrinsic(i.source(), new Operation(op.name(), op.form(), new Facts(random.nextBoolean() ? PathRule.SAME : PathRule.NONE, true)), i.args());
      }
      // The leaves of the toy parts: an Identity, a Const, a Var or a Break.
      default -> node;
    };
  }

  private static Tracking tracking(SplittableRandom random) {
    return random.nextBoolean() ? Tracking.PATH : Tracking.VALUE;
  }

  /**
   * How {@code executable} ran on {@link RandomParts#IN} under {@code c}'s step budget and stop, declaring
   * {@code stackBytes}: by its path variant when {@code paths} is not null, which then takes each result's keys.
   */
  private static Outcome outcome(Executable executable, Case c, long stackBytes, List<Object> paths) {
    RunState state = new RunState();
    state.begin(c.steps(), UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, stackBytes);
    List<Object> emitted = new ArrayList<>();
    Boolean answer = null;
    Object thrown = null;
    try {
      if (paths == null) {
        answer =
            executable.run(
                state,
                IN,
                v -> {
                  emitted.add(v);
                  return emitted.size() < c.stopAt();
                });
      } else {
        answer =
            executable.runPath(
                state,
                IN,
                (v, p) -> {
                  emitted.add(v);
                  paths.add(ListPaths.keys(p));
                  return emitted.size() < c.stopAt();
                });
      }
    } catch (FilterException e) {
      thrown = List.of("error", e.value());
    } catch (Termination t) {
      thrown = List.of("termination", String.valueOf(t.budget()));
    } catch (RuntimeException e) {
      thrown = "crash: " + e;
    }
    return new Outcome(List.copyOf(emitted), answer, thrown, state.steps(), state.built());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aPathVariantRunsAsItsRunDoes(long seed) {
    Case c = BindsPropertyTest.generate(seed);
    for (CompileMode mode : CompileMode.values()) {
      Executable executable = Compiler.compile(new Program(root(c, seed), List.of()), 0, mode);
      Outcome run = outcome(executable, c, UNLIMITED, null);
      assertThat(run.crashed()).as("seed %d, %s: whether the run crashed (%s)", seed, mode, run).isFalse();
      List<Object> pushed = new ArrayList<>();
      assertThat(outcome(executable, c, UNLIMITED, pushed)).as("seed %d, %s: the path variant in push form", seed, mode).isEqualTo(run);
      List<Object> heap = new ArrayList<>();
      assertThat(outcome(executable, c, 0, heap)).as("seed %d, %s: the path variant on the trampoline", seed, mode).isEqualTo(run);
      assertThat(heap).as("seed %d, %s: the paths on the trampoline, against push form", seed, mode).isEqualTo(pushed);
    }
  }

  /**
   * Witness that the property is not vacuous: the path variant gives a tracked result in 5 % of the seeds at least, and
   * an untracked one in as many.
   */
  @Test
  void theSeedsGiveTrackedAndUntrackedResults() {
    int tracked = 0;
    int untracked = 0;
    long[] seeds = seeds().toArray();
    for (long seed : seeds) {
      Case c = BindsPropertyTest.generate(seed);
      List<Object> paths = new ArrayList<>();
      outcome(Compiler.compile(new Program(root(c, seed), List.of()), 0, CompileMode.OPTIMISED), c, UNLIMITED, paths);
      if (paths.stream().anyMatch(p -> p != null)) {
        tracked++;
      }
      if (paths.contains(null)) {
        untracked++;
      }
    }
    assertThat(tracked).as("seeds with a tracked result").isGreaterThanOrEqualTo(seeds.length / 20);
    assertThat(untracked).as("seeds with an untracked result").isGreaterThanOrEqualTo(seeds.length / 20);
  }
}
