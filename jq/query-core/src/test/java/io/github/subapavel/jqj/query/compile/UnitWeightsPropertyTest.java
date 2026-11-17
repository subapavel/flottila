package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.RandomParts.AT;
import static io.github.subapavel.jqj.query.compile.RandomParts.NO;
import static io.github.subapavel.jqj.query.compile.RandomParts.TRUTH;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.RandomParts.Form;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
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
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Seeded property of the stack units (#111, ADR-0046 #111 amendment; docs/agents/environment.md, one case per seed):
 * every live push-form frame is charged to a live call's units or lies in the innermost body, so at any point of a run
 * the interpreter's Java frames stay below {@code U_f - 1 + D_f} units (ADR-0046, the invariant: no {@code T} term). A probe intrinsic counts them with a
 * {@link StackWalker} that shows hidden frames (lambda and proxy frames are units too): every frame of
 * {@code io.github.subapavel.jqj.query.} between the run's call of {@link Executable#run} and the probe, the probe's own
 * frames, a native's, excluded.
 *
 * <p>Each seed builds a program of 1 to 3 functions, each a recursion on a counter that calls itself or another
 * (mutual recursion), with or without a closure parameter run by a ParamCall and passed on, the recursive site wrapped
 * in 1 to 3 of Collect, Try, Alt, Label and Break, Pipe, Comma, Bind, Binds, Reduce and Advance, Foreach, AltBind and
 * If, and leaves and consumers of Identity, Const, Var and intrinsics (the probe, a Many, raising and empty ones):
 * the 20 kinds. #111's deep shapes are among them: a Collect per level (deep/collect), a Reduce step per level
 * (deep/reduce), a Label and an Alt per level (deep/first-or), and a consumer that runs a function emitting from under
 * Trys (deep/hold). It runs at {@code U_f} 1, 2, 5, 17 and 64 under three compiles: the conservative one, the
 * optimised one under a specialisation cap of 0 and the optimised one under {@code SPECIALISE_CAP} (ADR-0046 #116, R1;
 * ADR-0045 #117; #117 re-pin ruling W4). Each run must end as the run with the default stack does, with the same probe
 * count, and hold no units after. The optimised reference run under cap 0, which builds no copy, ends as the
 * conservative one does, steps included; the one under {@code SPECIALISE_CAP} has the conservative one's results, error
 * and probe count and at most its steps, since a copy's replaced ParamCall does not tick, and the same steps where the
 * compile built no copy.
 *
 * <p>The same seeds also build value-eligible recursion (ADR-0046 #116, R1): with no closure parameter and a single
 * probe as the leaf, a body whose wrappers are all at most one is value form in the optimised compile, and its calls run
 * by {@code eval}, which holds the push form's units (ADR-0047 #116).
 *
 * <p>U_f, D_f and T are read through {@link StackUnits} ({@code compile/StackUnits}, #111).
 */
class UnitWeightsPropertyTest {
  /** The unit limits the design's R3 names. */
  private static final List<Long> LIMITS = List.of(1L, 2L, 5L, 17L, 64L);

  /** The command line's declared stack (ADR-0017): the reference run's. */
  private static final long DEFAULT_STACK = 512 * 1024;

  /**
   * The thread of every run, 128 times the reference run's declared stack: that run, too, switches to the trampoline at
   * its own {@code U_f}, and this thread holds its frames up to it with room to spare.
   */
  private static final long THREAD = 64L << 20;

  private static final StackWalker WALKER = StackWalker.getInstance(Set.of(StackWalker.Option.SHOW_HIDDEN_FRAMES));

  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /**
   * The seeds, then two named cases of the #113 rulings (ADR-0046 #113 "Rulings on the red"): frames above units are a
   * weight defect, not a {@code b}. Each is the IR the Resolver builds for a calibration shape, its consumers made
   * intrinsics that pass their input on, and the probe at its leaf.
   *
   * <ul>
   *   <li>The wide body W: 16 layers per level, each {@code Pipe(Pipe(Collect(Bind(Try(<next>), s, Intrinsic)),
   *       Intrinsic), Intrinsic)}, the innermost {@code Bind(Pipe(dec, Call), v, Intrinsic)}, 50 levels.
   *   <li>The Collect-heavy descent of F-95: {@code Bind(Identity, n, Pipe(Collect^100(Pipe(dec, Call)), Var n))}, 40
   *       levels.
   * </ul>
   */
  static Stream<Arguments> cases() {
    return Stream.concat(
        seeds().mapToObj(seed -> Arguments.of("seed " + seed, (Supplier<Program>) () -> new Gen(seed, false).program())),
        Stream.of(
            Arguments.of("wide body W", (Supplier<Program>) () -> wideBody(50)),
            Arguments.of("Collect-heavy descent", (Supplier<Program>) () -> collectDescent(40))));
  }

  /** {@code if positive then <site> else (probe, 0) end} around one recursive function, run from {@code depth}. */
  private static Program recursion(int depth, Node site) {
    Node deepest = new Comma(AT, Gen.probe(), new Const(AT, 0));
    Node body = new If(AT, Gen.fn0("positive", (state, in) -> ((Integer) in) > 0 ? "yes" : NO), site, deepest, TRUTH);
    return new Program(new Pipe(AT, new Const(AT, depth), new Call(AT, 0, List.of(), List.of())), List.of(new Function(AT, 0, 0, body)));
  }

  private static Node pass(String name) {
    return Gen.fn0(name, (state, in) -> in);
  }

  private static Node collect(Node x) {
    return new Collect(AT, x, (elements, length) -> List.of(java.util.Arrays.copyOf(elements, length)));
  }

  private static Node dec() {
    return Gen.fn0("dec", (state, in) -> in instanceof Integer i ? i - 1 : in);
  }

  private static Program wideBody(int depth) {
    int slot = 0;
    Node x = new Bind(AT, new Pipe(AT, dec(), new Call(AT, 0, List.of(), List.of())), slot++, pass("plus1"));
    for (int layer = 0; layer < 16; layer++) {
      x = new Pipe(AT, new Pipe(AT, collect(new Bind(AT, new Try(AT, x, new Const(AT, "caught")), slot++, pass("object"))), pass("index0")), pass("indexA"));
    }
    return recursion(depth, x);
  }

  private static Program collectDescent(int depth) {
    Node x = new Pipe(AT, dec(), new Call(AT, 0, List.of(), List.of()));
    for (int c = 0; c < 100; c++) x = collect(x);
    return recursion(depth, new Bind(AT, new Identity(AT), 0, new Pipe(AT, x, new Var(AT, 0))));
  }

  /** The frames the probe saw in the current run, one per probe call. */
  private static final ThreadLocal<List<Integer>> FRAMES = ThreadLocal.withInitial(ArrayList::new);

  /** Interpreter frames between {@link #runProbed} and the caller of the probe. */
  private static int interpreterFrames() {
    return WALKER.walk(
        frames -> {
          int n = 0;
          for (StackWalker.StackFrame f : (Iterable<StackWalker.StackFrame>) frames::iterator) {
            String c = f.getClassName();
            if (c.startsWith(UnitWeightsPropertyTest.class.getName())) {
              if (f.getMethodName().equals("runProbed")) return n;
              continue;
            }
            if (c.startsWith("io.github.subapavel.jqj.query.")) n++;
          }
          throw new AssertionError("the probe ran outside runProbed");
        });
  }

  /**
   * How one run ended: its results, what it threw, its steps, and the frames at each probe. Outcomes and results are
   * compared by {@code equals} and printed by {@code String.valueOf} in the failure's description: a run's results can
   * nest a list per level, and AssertJ's representation formats a nested list on one line and then on several at every
   * level, so its message for a failure on them takes time exponential in the depth and hangs the fork (F-99; #117's
   * thread dump of a failing seed holds 68 nested {@code smartFormat} frames).
   */
  record Ended(List<Object> results, Object thrown, long steps, long units, List<Integer> frames) {
    /** The run without its frames, which differ between stacks. */
    List<Object> outcome() {
      return List.of(results, String.valueOf(thrown), steps, frames.size());
    }
  }

  /** Runs {@code program} declaring {@code stackBytes}, on this thread. */
  private static Ended runProbed(Executable e, long stackBytes) {
    FRAMES.get().clear();
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, stackBytes);
    List<Object> results = new ArrayList<>();
    Object thrown = null;
    try {
      e.run(state, null, v -> results.add(v));
    } catch (FilterException x) {
      thrown = List.of("error", x.value());
    } catch (Termination t) {
      thrown = List.of("termination", String.valueOf(t.budget()));
    }
    return new Ended(List.copyOf(results), thrown, state.steps(), StackUnits.units(state), List.copyOf(FRAMES.get()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void framesStayUnderTheLimit(String name, Supplier<Program> program) {
    onBigThread(name, () -> modes(name, program));
  }

  /** {@link #framesStayUnderTheLimit} over the value-eligible programs of the same seeds. */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void framesStayUnderTheLimitInValueFormRecursion(long seed) {
    onBigThread("seed " + seed, () -> modes("seed " + seed, () -> new Gen(seed, true).program()));
  }

  /**
   * Witness that the value-eligible programs are not vacuous: in at least a quarter of the seeds the optimised compile
   * runs function 0's body by its value form.
   */
  @Test
  void theValueSeedsRecurseByTheValueForm() {
    long value = seeds().filter(seed -> Compiler.compile(new Gen(seed, true).program(), 0, CompileMode.OPTIMISED).form(0, 0) == RunForm.VALUE).count();
    assertThat(value).as("seeds whose function 0 is value form, of %d", seeds().count()).isGreaterThanOrEqualTo(seeds().count() / 4);
  }

  /**
   * Witness that the third compile of {@link #modes} is not the second: in at least 10 of the seeds the optimised compile
   * under {@link Specialiser#SPECIALISE_CAP} builds a copy (#117 re-pin ruling W4).
   */
  @Test
  void someSeedsBuildACopy() {
    long copied = seeds().filter(seed -> Compiler.compile(new Gen(seed, false).program(), 0, CompileMode.OPTIMISED).frozen().specialised() > 0).count();
    assertThat(copied).as("seeds whose optimised compile builds a copy, of %d", seeds().count()).isGreaterThanOrEqualTo(10);
  }

  /**
   * {@link #check} in three compiles (#117 re-pin ruling W4). The conservative one. The optimised one under a
   * specialisation cap of 0, which builds no copy (ADR-0045 #117): its reference run ends as the conservative one does,
   * steps included, since the value form charges the push form's steps (ADR-0047 #116). And the optimised one under
   * {@link Specialiser#SPECIALISE_CAP}, whose copies run an argument in place of the ParamCall it replaces, without that
   * ParamCall's tick (SpecialiserPropertyTest's rule): its reference run has the conservative one's results, error and
   * probe count, and at most its steps, exactly its steps when the compile built no copy.
   */
  private static void modes(String name, Supplier<Program> program) {
    Ended conservative = check(name, Compiler.compile(program.get(), 0, CompileMode.CONSERVATIVE), "CONSERVATIVE");
    Ended unspecialised = check(name, Compiler.compile(program.get(), 0, CompileMode.OPTIMISED, 0), "OPTIMISED under cap 0");
    assertThat(unspecialised.outcome().equals(conservative.outcome()))
        .as("%s: the optimised reference run under cap 0, %s, equals the conservative one, %s", name, unspecialised.outcome(), conservative.outcome())
        .isTrue();

    Executable optimised = Compiler.compile(program.get(), 0, CompileMode.OPTIMISED);
    Ended specialised = check(name, optimised, "OPTIMISED");
    assertThat(specialised.results().equals(conservative.results()))
        .as("%s: the results of the optimised reference run, %s, equal the conservative one's, %s", name, specialised.results(), conservative.results())
        .isTrue();
    assertThat(String.valueOf(specialised.thrown())).as("%s: what the optimised reference run threw against the conservative one", name).isEqualTo(String.valueOf(conservative.thrown()));
    assertThat(specialised.frames().size()).as("%s: the probes of the optimised reference run against the conservative one's", name).isEqualTo(conservative.frames().size());
    assertThat(specialised.steps()).as("%s: the steps of the optimised reference run against the conservative one's", name).isLessThanOrEqualTo(conservative.steps());
    if (optimised.frozen().specialised() == 0) {
      assertThat(specialised.steps()).as("%s: the steps of the optimised reference run, no copy built, against the conservative one's", name).isEqualTo(conservative.steps());
    }
  }

  /** Runs {@code work} on a thread of {@link #THREAD} bytes and rethrows what it threw. */
  private static void onBigThread(String name, Runnable work) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread = new Thread(null, () -> {
      try {
        work.run();
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "units " + name, THREAD);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException x) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(x);
    }
    if (failure.get() instanceof Error error) throw error;
    if (failure.get() instanceof RuntimeException runtime) throw runtime;
  }

  /** The property for the compile {@code e}, named {@code mode} in the messages; the reference run, at the default stack. */
  private static Ended check(String name, Executable e, String mode) {
    Ended reference = runProbed(e, DEFAULT_STACK);
    assertThat(reference.frames()).as("%s, %s: the probes of the reference run", name, mode).isNotEmpty();
    long callFree = StackUnits.callFreeDepth(e);
    long starter = StackUnits.starterUnits(e);
    for (long limit : LIMITS) {
      long stackBytes = StackUnits.reserve() + StackUnits.bytesPerUnit() * (limit + callFree);
      assertThat(StackUnits.unitLimit(e, stackBytes)).as("%s, %s: U_f of stackBytes %d", name, mode, stackBytes).isEqualTo(limit);
      Ended run = runProbed(e, stackBytes);
      long bound = limit - 1 + callFree;
      assertThat(run.frames())
          .as("%s, %s, U_f %d, D_f %d, T %d: frames at each probe against %d", name, mode, limit, callFree, starter, bound)
          .allSatisfy(frames -> assertThat((long) frames).isLessThan(bound));
      assertThat(run.outcome().equals(reference.outcome()))
          .as("%s, %s, U_f %d: the run, %s, equals the default stack's, %s", name, mode, limit, run.outcome(), reference.outcome())
          .isTrue();
      assertThat(run.units()).as("%s, %s, U_f %d: units after the run", name, mode, limit).isZero();
    }
    assertThat(reference.units()).as("%s, %s: units after the reference run", name, mode).isZero();
    return reference;
  }

  /** The random program of one seed. Slots are unique across the program. */
  static final class Gen {
    private final SplittableRandom random;
    private int slot = 0;
    private final int[] closures;
    private final int depth;

    /** Whether the program is value-eligible: no closure parameter, and the leaf a single probe. */
    private final boolean value;

    /**
     * The generator of {@code seed}; with {@code value}, of its value-eligible program, from the same draws with every
     * closure parameter dropped.
     */
    Gen(long seed, boolean value) {
      random = new SplittableRandom(seed);
      closures = new int[1 + random.nextInt(3)];
      for (int i = 0; i < closures.length; i++) {
        int drawn = random.nextInt(2);
        closures[i] = value ? 0 : drawn;
      }
      depth = 20 + random.nextInt(181);
      this.value = value;
    }

    private int below(int n) {
      return random.nextInt(n);
    }

    private int fresh() {
      return slot++;
    }

    Program program() {
      List<Function> functions = new ArrayList<>();
      for (int i = 0; i < closures.length; i++) functions.add(new Function(AT, closures[i], 0, body(i)));
      Node main = new Pipe(AT, new Const(AT, depth), call(0, false));
      return new Program(below(2) == 0 ? main : new Collect(AT, main, (elements, length) -> List.of(java.util.Arrays.copyOf(elements, length))), List.copyOf(functions));
    }

    /** {@code if positive then <wrapped dec | call> else <leaf> end}. */
    private Node body(int f) {
      boolean param = closures[f] == 1;
      Node site = new Pipe(AT, fn0("dec", (state, in) -> ((Integer) in) - 1), call(below(closures.length), param));
      for (int k = 1 + below(3); k > 0; k--) site = wrap(site, param);
      // The deepest leaf probes first, so every run probes at its deepest point; a value-eligible one only probes.
      Node deepest = value ? probe() : new Comma(AT, probe(), leaf(2, List.of(), param));
      return new If(AT, fn0("positive", (state, in) -> ((Integer) in) > 0 ? "yes" : NO), site, deepest, TRUTH);
    }

    /** A Call of {@code fn}, passing its closure: the caller's parameter on, or a leaf. */
    private Node call(int fn, boolean param) {
      List<Node> args = closures[fn] == 0 ? List.of() : List.of(param && below(2) == 0 ? new ParamCall(AT, 0) : leaf(2, List.of(), false));
      return new Call(AT, fn, args, List.of());
    }

    /** One wrapper of the recursive site {@code x}: each runs it once per input, so results grow only linearly. */
    private Node wrap(Node x, boolean param) {
      return switch (below(13)) {
        case 0 -> new Collect(AT, x, (elements, length) -> List.of(java.util.Arrays.copyOf(elements, length)));
        case 1 -> new Try(AT, x, new Const(AT, "caught"));
        case 2 -> new Alt(AT, x, new Const(AT, "alt"), TRUTH);
        case 3 -> {
          int l = fresh();
          yield new Label(AT, l, new Comma(AT, x, new Break(AT, l)));
        }
        case 4 -> new Pipe(AT, x, consumer(2, List.of(), param));
        case 5 -> new Comma(AT, x, new Const(AT, "more"));
        case 6 -> {
          int s = fresh();
          yield new Bind(AT, x, s, new Var(AT, s));
        }
        case 7 -> {
          int a = fresh();
          int b = fresh();
          yield new Binds(AT, List.of(x, new Const(AT, "b")), List.of(a, b), new Var(AT, a));
        }
        case 8 -> {
          int st = fresh();
          int v = fresh();
          yield new Reduce(AT, new Const(AT, "init"), st, new Bind(AT, x, v, new Advance(AT, st, new Var(AT, v))));
        }
        case 9 -> {
          int st = fresh();
          int v = fresh();
          yield new Foreach(AT, new Const(AT, "init"), st, new Bind(AT, x, v, new Advance(AT, st, new Var(AT, v))));
        }
        case 10 -> {
          int v = fresh();
          int own = fresh();
          int group = fresh();
          yield new AltBind(AT, x, v, List.of(new Alternative(new Bind(AT, new Var(AT, v), own, new Identity(AT)), List.of(own))), List.of(group), new Var(AT, group));
        }
        case 11 -> new If(AT, new Const(AT, "yes"), x, new Const(AT, "never"), TRUTH);
        // deep/hold's consumer: each result runs a function at 0, which emits from its leaf, one collected result.
        default -> new Pipe(AT, x, new Collect(AT, new Pipe(AT, new Const(AT, 0), call(0, false)), (elements, length) -> List.of(java.util.Arrays.copyOf(elements, length))));
      };
    }

    /** A part of at most one result per input that may probe; {@code scope} the slots it may read. */
    private Node consumer(int d, List<Integer> scope, boolean param) {
      int pick = below(d <= 0 ? 6 : 12);
      return switch (pick) {
        case 0, 1 -> probe();
        case 2 -> new Identity(AT);
        case 3 -> scope.isEmpty() ? new Const(AT, "c") : new Var(AT, scope.get(below(scope.size())));
        case 4 -> new RandomParts.Tag("t", -1).build(Form.tail(0));
        case 5 -> below(4) == 0 ? new RandomParts.Raise("e", -1).build(Form.tail(0)) : new RandomParts.Nothing().build(Form.tail(0));
        case 6 -> new Collect(AT, leaf(d - 1, scope, param), (elements, length) -> List.of(java.util.Arrays.copyOf(elements, length)));
        case 7 -> new Try(AT, consumer(d - 1, scope, param), probe());
        case 8 -> new Pipe(AT, consumer(d - 1, scope, param), consumer(d - 1, scope, param));
        case 9 -> {
          int s = fresh();
          List<Integer> inner = new ArrayList<>(scope);
          inner.add(s);
          yield new Bind(AT, consumer(d - 1, scope, param), s, consumer(d - 1, inner, param));
        }
        case 10 -> new Try(AT, new Try(AT, new Try(AT, consumer(d - 1, scope, param), new Const(AT, "h")), new Const(AT, "h")), new Const(AT, "h"));
        default -> new If(AT, consumer(d - 1, scope, param), consumer(d - 1, scope, param), consumer(d - 1, scope, param), TRUTH);
      };
    }

    /** A part of any number of results that may probe, or run the closure parameter. */
    private Node leaf(int d, List<Integer> scope, boolean param) {
      int pick = below(d <= 0 ? 4 : 8);
      return switch (pick) {
        case 0 -> probe();
        case 1 -> param ? new ParamCall(AT, 0) : probe();
        case 2 -> new RandomParts.Over(List.of("x", "y"), below(5) == 0).build(Form.tail(0));
        case 3 -> consumer(0, scope, param);
        case 4 -> new Comma(AT, leaf(d - 1, scope, param), leaf(d - 1, scope, param));
        case 5 -> new Pipe(AT, leaf(d - 1, scope, param), consumer(d - 1, scope, param));
        case 6 -> new Alt(AT, leaf(d - 1, scope, param), probe(), TRUTH);
        default -> consumer(d, scope, param);
      };
    }

    static Node probe() {
      return fn0(
          "probe",
          (state, in) -> {
            FRAMES.get().add(interpreterFrames());
            return "p";
          });
    }

    static Node fn0(String name, Fn0 fn) {
      return new Intrinsic(AT, new Operation(name, fn), List.of());
    }
  }
}
