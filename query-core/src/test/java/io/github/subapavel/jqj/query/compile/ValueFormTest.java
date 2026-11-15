package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.RandomParts.outcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.query.compile.RandomParts.Form;
import io.github.subapavel.jqj.query.internal.interpret.AltBindOp;
import io.github.subapavel.jqj.query.internal.interpret.Argument;
import io.github.subapavel.jqj.query.internal.interpret.BreakOp;
import io.github.subapavel.jqj.query.internal.interpret.IdentityOp;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicManyOp;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.ParamCallOp;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.RandomParts.Part;
import io.github.subapavel.jqj.query.ir.Advance;
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
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Collector;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The value form at query-core's seam (#116, ADR-0047 "Amendment (ticket #116)", ADR-0046 "Amendment (ticket #116)",
 * ADR-0008, F-56): in the optimised compile a node whose cardinality is at most one and that may not break out runs by
 * {@code eval}, which up to its result ticks, charges and writes exactly as {@code run} does and then returns it; its
 * push parent, a Call or the run entry emits it. So the value form changes how deep a run nests and how long a call's
 * activation lives, and nothing a run's results, errors, terminations or steps show. The conservative compile never runs
 * it and is the oracle here. Over toy formats (founding §14): {@link RandomParts}' strings and lists, and integers for
 * the hand-made recursions.
 *
 * <p>A probe is an Fn0 that gives its input and records how many Java frames are on the stack where it runs, hidden
 * frames included ({@code StackWalker} with {@code SHOW_HIDDEN_FRAMES}), so a lambda sink's frame counts. Both modes of
 * one case run from the same frame of this class, so their depths compare.
 *
 * <p>Seam (the design's, ADR-0047 #116 "RunForm"): {@code Executable.form(int function, int id)}, -1 for main and a
 * freeze id as {@code query.analysis.Numbering} gives it, returning the public enum {@code query.compile.RunForm}, whose
 * constants are {@code PUSH} and {@code VALUE}. The rest is #109's: {@code RunState.begin} with a recursion-depth
 * budget and {@code stackBytes}, 0 running main and every body on the trampoline (ADR-0046 and its #109 amendment).
 * S2b.5's {@code U_f} (#111) is not on the branch at dbae0f0, so the unit limit here is the one every call of a run
 * declaring {@code stackBytes} 0 is past.
 */
class ValueFormTest {
  private static final Source AT = new Source(0, 1);

  private static final long UNLIMITED = Long.MAX_VALUE;

  private static final int NEVER_STOPS = Integer.MAX_VALUE;

  /** A thread every hand-made push-form run here fits on: the activation case's 4 001 live calls among them. */
  private static final long BIG = 256L << 20;

  /** The thread of the trampoline runs: the push form of 3 000 levels does not fit it (TrampolineTest's margin). */
  private static final long SMALL = 256 * 1024;

  private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.SHOW_HIDDEN_FRAMES);

  private static final Object YES = "yes";

  private static final Object NO = "no";

  private static final Truth TRUTH = YES::equals;

  /** The toy array: a list of the elements, a Java null among them. */
  private static final Collector LIST = (elements, length) -> Arrays.asList(Arrays.copyOf(elements, length));

  /** An Fn0 that gives its input and records the Java depth it ran at, hidden frames included. */
  static final class Probe {
    final List<Long> depths = new ArrayList<>();

    Intrinsic node() {
      return new Intrinsic(
          AT,
          new Operation(
              "probe",
              (Fn0)
                  (state, in) -> {
                    depths.add(WALKER.walk(Stream::count));
                    return in;
                  }),
          List.of());
    }

    long max() {
      return depths.stream().mapToLong(Long::longValue).max().orElse(-1);
    }
  }

  private static Intrinsic call(String name, Fn0 fn) {
    return new Intrinsic(AT, new Operation(name, fn), List.of());
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  /** {@code if . == 0 then base else otherwise end} on an integer input. */
  private static If ifZero(Node base, Node otherwise) {
    return new If(AT, call("zero", (state, in) -> (Integer) in == 0 ? YES : NO), base, otherwise, TRUTH);
  }

  /** {@code if . > k then then else otherwise end} on an integer input. */
  private static If ifAbove(int k, Node then, Node otherwise) {
    return new If(AT, call("above" + k, (state, in) -> (Integer) in > k ? YES : NO), then, otherwise, TRUTH);
  }

  private static Intrinsic dec() {
    return call("dec", (state, in) -> (Integer) in - 1);
  }

  private static Intrinsic inc() {
    return call("inc", (state, in) -> (Integer) in + 1);
  }

  /** {@code $slot + 1} */
  private static Intrinsic incOf(int slot) {
    return new Intrinsic(AT, new Operation("inc", (Fn1) (state, in, a) -> (Integer) a + 1), List.of(new Var(AT, slot)));
  }

  /** A call of function {@code fn} with the value arguments {@code values}, each a Var of the caller's frame. */
  private static Call f(int fn, int... values) {
    List<Node> args = new ArrayList<>();
    for (int slot : values) {
      args.add(new Var(AT, slot));
    }
    return new Call(AT, fn, List.of(), List.of(args.toArray(Node[]::new)));
  }

  /**
   * {@code def f: if . == 0 then base else (. - 1 | f) as $x | $x + 1 end; n | f}: a value-form recursion, as
   * value-form/a-value-form-recursion-3000-deep (the reference binary 1.8.2: 3000 on 3000, recorded).
   */
  private static Program valueRecursion(int n, Supplier<Node> base) {
    Node body = ifZero(base.get(), new Bind(AT, new Pipe(AT, dec(), f(0)), 0, incOf(0)));
    return new Program(new Pipe(AT, c(n), f(0)), List.of(new Function(AT, 0, 0, body)));
  }

  /**
   * {@code def f: if . == 0 then base else (. - 1 | f) | (0 | f) end; n | f}, the design's activation case: the reference binary 1.8.2
   * gives 0 for n = 3 and n = 2000 (.logs/116-jqref.log).
   */
  private static Program activations(int n, Supplier<Node> base) {
    Node body = ifZero(base.get(), new Pipe(AT, new Pipe(AT, dec(), f(0)), new Pipe(AT, c(0), f(0))));
    return new Program(new Pipe(AT, c(n), f(0)), List.of(new Function(AT, 0, 0, body)));
  }

  /**
   * {@code def f: if . > 3 then . else ((. + 1), error("x")) | f end; 0 | f}, F-56's third probe in toy form: the
   * recursion comes after a comma, so f is many and runs on after each result.
   */
  private static Program afterAComma() {
    Fn0 raise =
        (state, in) -> {
          throw new FilterException("x");
        };
    Node body = ifAbove(3, new Identity(AT), new Pipe(AT, new Comma(AT, inc(), call("error", raise)), f(0)));
    return new Program(new Pipe(AT, c(0), f(0)), List.of(new Function(AT, 0, 0, body)));
  }

  /**
   * X2 in toy form: {@code [label $l | def g: if . > 3 then G else . + 1 | g end; def f: if . > 1 then g else . + 1 |
   * f end; (0 | f), 7]}, f fn0 and g fn1, each taking main's label token as its value parameter, slot 0. With {@code
   * breaks}, G is {@code break $l}: f and g may break out and stay push form. Without, G is {@code .} (X2-dot): their
   * roots run by eval.
   */
  private static Program anotherGroup(boolean breaks) {
    Node g = ifAbove(3, breaks ? new Break(AT, 0) : new Identity(AT), new Pipe(AT, inc(), f(1, 0)));
    Node fBody = ifAbove(1, f(1, 0), new Pipe(AT, inc(), f(0, 0)));
    Node main = new Collect(AT, new Label(AT, 0, new Comma(AT, new Pipe(AT, c(0), f(0, 0)), c(7))), LIST);
    return new Program(main, List.of(new Function(AT, 0, 1, fBody), new Function(AT, 0, 1, g)));
  }

  /** A case of the properties: its name, a builder of fresh programs, its step budget and the result its sink stops at. */
  record Case(String name, Supplier<Program> program, long steps, int stopAt) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Case seeded(String family, long seed, Part root, int free, long steps, int stopAt) {
    return new Case(family + " seed " + seed, () -> new Program(root.build(Form.tail(free)), List.of()), steps, stopAt);
  }

  /** The hand-made call cases of the properties, each run to its end. */
  static List<Case> callCases() {
    return List.of(
        new Case("value-form recursion, 30 levels", () -> valueRecursion(30, () -> c(0)), UNLIMITED, NEVER_STOPS),
        new Case("activation case, 3 levels", () -> activations(3, () -> c(0)), UNLIMITED, NEVER_STOPS),
        new Case("activation case, 6 levels, stopping at the first result", () -> activations(6, () -> c(0)), UNLIMITED, 1),
        new Case("F-56's recursion after a comma", ValueFormTest::afterAComma, UNLIMITED, NEVER_STOPS),
        new Case("X2", () -> anotherGroup(true), UNLIMITED, NEVER_STOPS),
        new Case("X2-dot", () -> anotherGroup(false), UNLIMITED, NEVER_STOPS));
  }

  /**
   * The cases of the properties: each seed of {@link BindsPropertyTest} and of {@link IfTailPropertyTest}, in the tail
   * form of {@link RandomParts}, with the seed's step budget and stop, then the hand-made call cases.
   */
  static Stream<Case> cases() {
    Stream<Case> binds =
        BindsPropertyTest.seeds()
            .mapToObj(
                seed -> {
                  BindsPropertyTest.Case c = BindsPropertyTest.generate(seed);
                  return seeded("binds", seed, c.root(), c.free(), c.steps(), c.stopAt());
                });
    Stream<Case> ifs =
        IfTailPropertyTest.seeds()
            .mapToObj(
                seed -> {
                  IfTailPropertyTest.Case c = IfTailPropertyTest.generate(seed);
                  return seeded("if", seed, c.root(), c.free(), c.steps(), c.stopAt());
                });
    return Stream.concat(Stream.concat(binds, ifs), callCases().stream());
  }

  static Stream<Arguments> arguments() {
    return cases().map(c -> Arguments.argumentSet(c.name(), c));
  }

  /**
   * The value form charges the push form's steps (ADR-0047 #116, the eval contract; ADR-0020's half-budget rule): the
   * optimised run ends as the conservative one does, with the same results, answer, error or termination, steps and bytes
   * built, at the case's own step budget and stop, run to its end, and at half the steps its end took, where both end by
   * the step budget after the same output prefix.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("arguments")
  void valueFormChargesThePushFormsSteps(Case c) {
    Outcome own = outcome(c.program().get(), CompileMode.CONSERVATIVE, c.steps(), c.stopAt(), UNLIMITED, UNLIMITED);
    assertThat(own.crashed()).as("%s: whether the conservative run crashed (%s)", c, own).isFalse();
    assertThat(outcome(c.program().get(), CompileMode.OPTIMISED, c.steps(), c.stopAt(), UNLIMITED, UNLIMITED)).as("%s: the optimised run at the case's budget and stop", c).isEqualTo(own);

    Outcome full = outcome(c.program().get(), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED);
    assertThat(outcome(c.program().get(), CompileMode.OPTIMISED, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED)).as("%s: the optimised run to its end", c).isEqualTo(full);
    if (full.steps() >= 2) {
      long half = full.steps() / 2;
      Outcome halved = outcome(c.program().get(), CompileMode.CONSERVATIVE, half, NEVER_STOPS, UNLIMITED, UNLIMITED);
      assertThat(halved.kind()).as("%s: how the conservative run ends at %d of its %d steps (%s)", c, half, full.steps(), halved).isEqualTo("termination");
      assertThat(outcome(c.program().get(), CompileMode.OPTIMISED, half, NEVER_STOPS, UNLIMITED, UNLIMITED)).as("%s: the optimised run at %d steps", c, half).isEqualTo(halved);
    }
  }

  /** {@code node} with every Intrinsic {@code i} that is no argument replaced by {@code i | probe}. */
  private static Node probed(Node node, Probe probe) {
    return switch (node) {
      case Intrinsic i -> new Pipe(AT, i, probe.node());
      case Pipe p -> new Pipe(p.source(), probed(p.left(), probe), probed(p.right(), probe));
      case Comma k -> new Comma(k.source(), probed(k.left(), probe), probed(k.right(), probe));
      case Bind b -> new Bind(b.source(), probed(b.value(), probe), b.slot(), probed(b.body(), probe));
      case Binds b -> new Binds(b.source(), b.values().stream().map(v -> probed(v, probe)).toList(), b.slots(), probed(b.body(), probe));
      case Collect k -> new Collect(k.source(), probed(k.body(), probe), k.collector());
      case If i -> new If(i.source(), probed(i.cond(), probe), probed(i.then(), probe), probed(i.otherwise(), probe), i.truth());
      case Label l -> new Label(l.source(), l.slot(), probed(l.body(), probe));
      // Identity, Const, Var and Break: the leaves RandomParts builds, kept as they are.
      default -> node;
    };
  }

  /** The deepest probe of {@code program} in {@code mode}, run to its end on the calling thread; -1 when none ran. */
  private static long deepest(Supplier<Program> program, Probe probe, CompileMode mode) {
    outcome(program.get(), mode, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED);
    return probe.max();
  }

  /** The depth cases: each seed's root with a probe after each Intrinsic, then two hand-made calls with a probe at the base. */
  static Stream<Arguments> depthArguments() {
    Stream<Arguments> seeds =
        Stream.concat(
            BindsPropertyTest.seeds().mapToObj(seed -> Arguments.argumentSet("binds seed " + seed, "binds", seed)),
            IfTailPropertyTest.seeds().mapToObj(seed -> Arguments.argumentSet("if seed " + seed, "if", seed)));
    return Stream.concat(
        seeds, Stream.of(Arguments.argumentSet("value-form recursion, 50 levels", "recursion", 50L), Arguments.argumentSet("activation case, 5 levels", "activations", 5L)));
  }

  /** The program of a depth case in its family, its probes recording into {@code probe}. */
  private static Program depthCase(String family, long n, Probe probe) {
    return switch (family) {
      case "binds" -> {
        BindsPropertyTest.Case c = BindsPropertyTest.generate(n);
        yield new Program(probed(c.root().build(Form.tail(c.free())), probe), List.of());
      }
      case "if" -> {
        IfTailPropertyTest.Case c = IfTailPropertyTest.generate(n);
        yield new Program(probed(c.root().build(Form.tail(c.free())), probe), List.of());
      }
      case "recursion" -> valueRecursion((int) n, probe::node);
      case "activations" -> activations((int) n, () -> new Pipe(AT, c(0), probe.node()));
      default -> throw new IllegalArgumentException("no depth family " + family);
    };
  }

  /**
   * The value form nests no deeper than the push form (ADR-0046 #116, R1: an op's eval reaches each child and each
   * starter through no more Java frames than its run does): the deepest probe of the optimised run is at most the
   * conservative run's, and both runs ran the same probes.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("depthArguments")
  void valueFormNestsNoDeeperThanThePushForm(String family, long n) {
    Probe conservative = new Probe();
    long push = deepest(() -> depthCase(family, n, conservative), conservative, CompileMode.CONSERVATIVE);
    Probe optimised = new Probe();
    long value = deepest(() -> depthCase(family, n, optimised), optimised, CompileMode.OPTIMISED);

    assertThat(optimised.depths).as("%s %d: the probes the optimised run ran", family, n).hasSameSizeAs(conservative.depths);
    assertThat(value).as("%s %d: the deepest probe of the optimised run, the conservative one's at %d", family, n, push).isLessThanOrEqualTo(push);
  }

  /**
   * Witness that the depth property is not vacuous: the probes ran in three seeds of four at least (at dbae0f0 in 640 of
   * the 800), and over the seeds the optimised run nests less than the conservative one somewhere, so the value form
   * ran.
   */
  @Test
  void theSeedsNestLessInTheValueForm() {
    int probed = 0;
    int shallower = 0;
    int seeds = 0;
    for (String family : List.of("binds", "if")) {
      for (long seed = 0; seed < 400; seed++) {
        seeds++;
        long s = seed;
        Probe conservative = new Probe();
        long push = deepest(() -> depthCase(family, s, conservative), conservative, CompileMode.CONSERVATIVE);
        Probe optimised = new Probe();
        long value = deepest(() -> depthCase(family, s, optimised), optimised, CompileMode.OPTIMISED);
        if (push >= 0) {
          probed++;
        }
        if (value < push) {
          shallower++;
        }
      }
    }

    assertThat(probed).as("seeds whose probes ran, of %d", seeds).isGreaterThanOrEqualTo(seeds * 3 / 4);
    assertThat(shallower).as("seeds whose optimised run nests less than the conservative one, of %d", seeds).isPositive();
  }

  /**
   * In {@code [1] | probe} the Pipe is value form (card ONE, no break): the run entry evals it, the Collect returns its
   * array and the probe runs after the Collect returned, so strictly fewer frames lie below the probe in the optimised
   * run than in the conservative one, where the probe runs inside the Collect's emit.
   */
  @Test
  void valueFormRunsTheConsumerAfterItReturned() {
    Probe conservative = new Probe();
    Supplier<Program> push = () -> new Program(new Pipe(AT, new Collect(AT, c(1), LIST), conservative.node()), List.of());
    Probe optimised = new Probe();
    Supplier<Program> value = () -> new Program(new Pipe(AT, new Collect(AT, c(1), LIST), optimised.node()), List.of());
    assertThat(Compiler.compile(value.get(), 0, CompileMode.OPTIMISED).form(-1, 0)).as("the run form of the Pipe under OPTIMISED").isEqualTo(RunForm.VALUE);
    assertThat(Compiler.compile(push.get(), 0, CompileMode.CONSERVATIVE).form(-1, 0)).as("the run form of the Pipe under CONSERVATIVE").isEqualTo(RunForm.PUSH);

    assertThat(outcome(push.get(), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED).results()).as("results of [1] | probe under CONSERVATIVE").isEqualTo(List.of(List.of(1)));
    assertThat(outcome(value.get(), CompileMode.OPTIMISED, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED).results()).as("results of [1] | probe under OPTIMISED").isEqualTo(List.of(List.of(1)));
    assertThat(conservative.depths).as("probes of the conservative run").hasSize(1);
    assertThat(optimised.depths).as("probes of the optimised run").hasSize(1);
    assertThat(optimised.max()).as("frames below the probe under OPTIMISED, %d under CONSERVATIVE", conservative.max()).isLessThan(conservative.max());
  }

  /** What a run of {@code program} in {@code mode} emitted, a Java null printed as null, and its answer. */
  private static String emitted(Program program, CompileMode mode) {
    RunState state = new RunState();
    state.begin(UNLIMITED, UNLIMITED, UNLIMITED);
    List<Object> results = new ArrayList<>();
    boolean answer =
        Compiler.compile(program, 0, mode)
            .run(
                state,
                "in",
                v -> {
                  results.add(v);
                  return true;
                });
    return results + " " + answer;
  }

  private static Intrinsic nil() {
    return call("nil", (state, in) -> null);
  }

  /** Each null case: a program in which a value-form node gives Java null, the node's body and id, and what the run emits. */
  static Stream<Arguments> nulls() {
    Fn0 seen = (state, in) -> "saw " + in;
    return Stream.of(
        Arguments.argumentSet("the run entry emits the root's null", (Supplier<Program>) () -> new Program(nil(), List.of()), -1, 0, "[null] true"),
        Arguments.argumentSet(
            "a push Comma emits its value-form left side's null", (Supplier<Program>) () -> new Program(new Comma(AT, nil(), c("after")), List.of()), -1, 1, "[null, after] true"),
        Arguments.argumentSet(
            "a Bind runs its body on a null value", (Supplier<Program>) () -> new Program(new Bind(AT, nil(), 0, new Collect(AT, new Var(AT, 0), LIST)), List.of()), -1, 1, "[[null]] true"),
        Arguments.argumentSet("a Pipe runs its right side on a null", (Supplier<Program>) () -> new Program(new Pipe(AT, nil(), call("seen", seen)), List.of()), -1, 1, "[saw null] true"),
        Arguments.argumentSet(
            "a Call emits its body's null",
            (Supplier<Program>) () -> new Program(new Comma(AT, f(0), c("after")), List.of(new Function(AT, 0, 0, nil()))),
            0,
            0,
            "[null, after] true"));
  }

  /**
   * Java null is a result (ADR-0047 #116: eval returns NONE for no result, and null is a value): where a value-form node
   * gives null, its parent, the Call or the run entry takes it as the one result, in OPTIMISED as in CONSERVATIVE. The
   * node gives null by an Fn0 of the toy format, whose values may be null.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("nulls")
  void aNullValueIsAResult(Supplier<Program> program, int body, int id, String expected) {
    assertThat(Compiler.compile(program.get(), 0, CompileMode.OPTIMISED).form(body, id)).as("the run form of the null's node under OPTIMISED").isEqualTo(RunForm.VALUE);

    assertThat(emitted(program.get(), CompileMode.CONSERVATIVE)).as("what the conservative run emitted").isEqualTo(expected);
    assertThat(emitted(program.get(), CompileMode.OPTIMISED)).as("what the optimised run emitted").isEqualTo(expected);
  }

  /** What {@code work} gave on a new thread of {@code stack} bytes, or {@code "threw "} and what it threw. */
  private static String on(long stack, Supplier<String> work) throws InterruptedException {
    AtomicReference<String> got = new AtomicReference<>();
    Throwable thrown = FreshJvm.on(stack, () -> got.set(work.get()));
    return thrown == null ? got.get() : "threw " + thrown;
  }

  /**
   * A value-form Call at the unit limit starts the trampoline, and the heap forms never call eval (ADR-0047 #116): the
   * 3 000-level value-form recursion of value-form/a-value-form-recursion-3000-deep (the reference binary 1.8.2: 3000), declaring
   * {@code stackBytes} 0 so that every call is past the limit, gives the same results and steps in OPTIMISED as in
   * CONSERVATIVE, and as the push form on a large thread, on a thread its push form does not fit. Its base's probe runs
   * as deep at 3 000 levels as at 3: no level's eval stays on the Java stack.
   */
  @Test
  void valueFormCallAtTheUnitLimitStartsTheTrampoline() throws InterruptedException {
    assertThat(Compiler.compile(valueRecursion(3, () -> c(0)), 0, CompileMode.OPTIMISED).form(0, 0)).as("the run form of f's root under OPTIMISED").isEqualTo(RunForm.VALUE);
    assertThat(Compiler.compile(valueRecursion(3, () -> c(0)), 0, CompileMode.OPTIMISED).form(-1, 0)).as("the run form of main under OPTIMISED").isEqualTo(RunForm.VALUE);

    String pushed = on(BIG, () -> outcome(valueRecursion(3000, () -> c(0)), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED).toString());
    assertThat(pushed).as("the push form of 3000 levels on a large thread").startsWith("Outcome[results=[3000], answer=true, thrown=null, ");
    assertThat(on(SMALL, () -> outcome(valueRecursion(3000, () -> c(0)), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED).toString()))
        .as("the push form of 3000 levels on the small thread")
        .startsWith("Outcome[results=[], answer=null, thrown=[termination, null], ");
    assertThat(on(SMALL, () -> outcome(valueRecursion(3000, () -> c(0)), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, 0).toString()))
        .as("3000 levels under CONSERVATIVE with stackBytes 0")
        .isEqualTo(pushed);
    assertThat(on(SMALL, () -> outcome(valueRecursion(3000, () -> c(0)), CompileMode.OPTIMISED, UNLIMITED, NEVER_STOPS, UNLIMITED, 0).toString()))
        .as("3000 levels under OPTIMISED with stackBytes 0")
        .isEqualTo(pushed);

    long[] depths = new long[2];
    int[] levels = {3, 3000};
    for (int k = 0; k < levels.length; k++) {
      Probe probe = new Probe();
      int n = levels[k];
      String ran = on(SMALL, () -> outcome(valueRecursion(n, probe::node), CompileMode.OPTIMISED, UNLIMITED, NEVER_STOPS, UNLIMITED, 0).toString());
      assertThat(ran).as("%d levels with a probe at the base under OPTIMISED with stackBytes 0", n).startsWith("Outcome[results=[" + n + "], answer=true, thrown=null, ");
      assertThat(probe.depths).as("probes of %d levels", n).hasSize(1);
      depths[k] = probe.max();
    }
    assertThat(depths[1]).as("frames below the base's probe at 3000 levels, at 3 levels %d", depths[0]).isEqualTo(depths[0]);
  }

  /** The end of the activation case of {@code n} levels in {@code mode} under a recursion-depth budget of {@code depth}. */
  private static String endOfActivations(int n, CompileMode mode, long depth) throws InterruptedException {
    return on(BIG, () -> {
      Outcome o = outcome(activations(n, () -> c(0)), mode, UNLIMITED, NEVER_STOPS, depth, UNLIMITED);
      return o.results() + " " + o.thrown();
    });
  }

  /**
   * A value-form Call's activation ends when its eval returns (ADR-0046 #116, C2). In {@code def f: if . == 0 then 0
   * else (. - 1 | f) | (0 | f) end; n | f} (the reference binary 1.8.2: 0 for n = 3 and n = 2000) the push form runs {@code 0 | f}
   * inside the emit of {@code . - 1 | f}, so each level's left call is still live while its right call runs: 2n + 1
   * live activations at the deepest, main's Call included. The value form runs the right call after the left one
   * returned: n + 1, the left chain. Each count is pinned at its edge by the recursion-depth budget: the run gives 0 at
   * the count and ends by the budget one below it.
   */
  @Test
  void valueFormCallEndsItsActivationAtItsResult() throws InterruptedException {
    assertThat(Compiler.compile(activations(3, () -> c(0)), 0, CompileMode.OPTIMISED).form(0, 0)).as("the run form of f's root under OPTIMISED").isEqualTo(RunForm.VALUE);
    int[] levels = {3, 2000};
    for (int n : levels) {
      assertThat(endOfActivations(n, CompileMode.CONSERVATIVE, 2L * n + 1)).as("n = %d under CONSERVATIVE at %d activations", n, 2 * n + 1).isEqualTo("[0] null");
      assertThat(endOfActivations(n, CompileMode.CONSERVATIVE, 2L * n)).as("n = %d under CONSERVATIVE at %d activations", n, 2 * n).isEqualTo("[] [termination, RECURSION_DEPTH]");
    }
    for (int n : levels) {
      assertThat(endOfActivations(n, CompileMode.OPTIMISED, n + 1L)).as("n = %d under OPTIMISED at %d activations", n, n + 1).isEqualTo("[0] null");
      assertThat(endOfActivations(n, CompileMode.OPTIMISED, n)).as("n = %d under OPTIMISED at %d activations", n, n).isEqualTo("[] [termination, RECURSION_DEPTH]");
    }
  }

  /** The unit limit forced on the value-form recursion of {@code n} levels with {@code base}. */
  private static final long FORCED = 4;

  /** The {@code stackBytes} at which the compile of {@code program} has {@code U_f} {@link #FORCED} (#111). */
  private static long forced(Program program) {
    Executable e = Compiler.compile(program, 0, CompileMode.OPTIMISED);
    long stackBytes = StackUnits.reserve() + StackUnits.bytesPerUnit() * (FORCED + StackUnits.callFreeDepth(e));
    assertThat(StackUnits.unitLimit(e, stackBytes)).as("U_f of %d stack bytes", stackBytes).isEqualTo(FORCED);
    return stackBytes;
  }

  /**
   * A value-form Call at a small forced unit limit starts the trampoline (ADR-0047 #116, ADR-0046 #111): at {@code U_f}
   * 4 the 3 000-level value-form recursion of value-form/a-value-form-recursion-3000-deep (the reference binary 1.8.2: 3000) gives
   * the push form's results and steps in both modes, and so does the same recursion with a base that gives no result,
   * whose starter's trampoline hands nothing back. Each runs on a thread of four times its declared stack (ADR-0046
   * "Where the stack is tested"). The base's probe runs as deep at 3 000 levels as at 30, so every call past the limit
   * ran on the driver, and none by eval on the Java stack.
   */
  @Test
  void valueFormCallAtASmallUnitLimitStartsTheTrampoline() throws InterruptedException {
    Supplier<Node> nothing = () -> call("nothing", (state, in) -> CallForm.NONE);
    assertThat(Compiler.compile(valueRecursion(3, nothing), 0, CompileMode.OPTIMISED).form(0, 0)).as("the run form of f's root with no base result").isEqualTo(RunForm.VALUE);
    List<Supplier<Node>> bases = List.of(() -> c(0), nothing);
    for (Supplier<Node> base : bases) {
      String pushed = on(BIG, () -> outcome(valueRecursion(3000, base), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED).toString());
      long stackBytes = forced(valueRecursion(3000, base));
      for (CompileMode mode : CompileMode.values()) {
        assertThat(on(4 * stackBytes, () -> outcome(valueRecursion(3000, base), mode, UNLIMITED, NEVER_STOPS, UNLIMITED, stackBytes).toString()))
            .as("3000 levels under %s at U_f %d, the push form's %s", mode, FORCED, pushed)
            .isEqualTo(pushed);
      }
    }

    long[] depths = new long[2];
    int[] levels = {30, 3000};
    for (int k = 0; k < levels.length; k++) {
      Probe probe = new Probe();
      int n = levels[k];
      long stackBytes = forced(valueRecursion(n, probe::node));
      String ran = on(4 * stackBytes, () -> outcome(valueRecursion(n, probe::node), CompileMode.OPTIMISED, UNLIMITED, NEVER_STOPS, UNLIMITED, stackBytes).toString());
      assertThat(ran).as("%d levels with a probe at the base under OPTIMISED at U_f %d", n, FORCED).startsWith("Outcome[results=[" + n + "], answer=true, thrown=null, ");
      assertThat(probe.depths).as("probes of %d levels", n).hasSize(1);
      depths[k] = probe.max();
    }
    assertThat(depths[1]).as("frames below the base's probe at 3000 levels, at 30 levels %d", depths[0]).isEqualTo(depths[0]);
  }

  /** {@code label $l | break $l}: no result, at most one and no break out, so value form (cardinality NONE). */
  private static Label none(int l) {
    return new Label(AT, l, new Break(AT, l));
  }

  private static Intrinsic nothing() {
    return call("nothing", (state, in) -> CallForm.NONE);
  }

  private static Intrinsic raise() {
    return call(
        "raise",
        (state, in) -> {
          throw new FilterException("x");
        });
  }

  private static Program main(Node root, Function... functions) {
    return new Program(root, List.of(functions));
  }

  /**
   * {@code def f(g): if . == "yes" then base else "yes" | f(argument) end}, the body of f, fn0 of one closure parameter:
   * on main's input {@code "in"} it calls itself once, then gives {@code base}. Its inner call passes {@code argument},
   * which is no bare ParamCall of g, so g is not invariant and specialisation makes no copy of f (ADR-0045 "Amendment
   * (ticket #117)"): main's Call runs f with its real closure, as #116's shape needs (#117 re-pin ruling V1).
   */
  private static Node recursing(Node base, Node argument) {
    return new If(AT, new Identity(AT), base, new Pipe(AT, c(YES), new Call(AT, 0, List.of(argument), List.of())), TRUTH);
  }

  /** A shape: its name, its program, a node and its expected run form, a part of it and its form, and its results. */
  private static Arguments shape(String name, Supplier<Program> program, int body, int id, RunForm form, int partBody, int part, RunForm partForm, String results) {
    return Arguments.argumentSet(name, program, body, id, form, partBody, part, partForm, results);
  }

  /**
   * The shapes where a node and one of its parts run in different forms, each pinned by freeze id: a value-form node
   * over a push part that gives many results, which the facts let through only where what follows gives none, and a
   * push node over a value-form part, whose value or no result it takes. The five shapes whose function takes a closure
   * run {@code recursing}, so specialisation makes no copy and main's Call keeps its real closure (#117 re-pin ruling
   * V1); SpecialiserTest pins what a copy makes of #116's first forms of them. The binding closure's Var reads main's
   * slot one frame out (ADR-0045 #107), the value form of an outer-variable read; in a copy that read would be a
   * trailing value of the copy's own frame (ADR-0045 #117 S4), so only a real closure keeps it.
   */
  static Stream<Arguments> shapes() {
    return Stream.of(
        shape("a value-form If on a condition of many results", () -> main(new Collect(AT, new If(AT, new Comma(AT, c(YES), c(NO)), none(0), none(1), TRUTH), LIST)), -1, 1, RunForm.VALUE, -1, 2, RunForm.PUSH, "[[]]"),
        shape(
            "a value-form Binds on a value of many results",
            () -> main(new Collect(AT, new Binds(AT, List.of(new Comma(AT, c(1), c(2)), c(3)), List.of(0, 1), none(2)), LIST)),
            -1,
            1,
            RunForm.VALUE,
            -1,
            2,
            RunForm.PUSH,
            "[[]]"),
        shape("a value-form Foreach on an init of many results", () -> main(new Collect(AT, new Foreach(AT, new Comma(AT, c(1), c(2)), 0, none(1)), LIST)), -1, 1, RunForm.VALUE, -1, 2, RunForm.PUSH, "[[]]"),
        shape(
            "a value-form Reduce on a Many init of no result, its each only recursing",
            () -> main(new Reduce(AT, new Intrinsic(AT, new Operation("empty", EMPTY), List.of()), 0, f(0)), new Function(AT, 0, 0, f(0))),
            -1,
            0,
            RunForm.VALUE,
            -1,
            1,
            RunForm.PUSH,
            "[]"),
        shape("a push Try on a value-form body of no result", () -> main(new Try(AT, nothing(), new Comma(AT, c(1), c(2)))), -1, 0, RunForm.PUSH, -1, 1, RunForm.VALUE, "[]"),
        shape("a push Try on a value-form body", () -> main(new Try(AT, c(1), new Comma(AT, c(2), c(3)))), -1, 0, RunForm.PUSH, -1, 1, RunForm.VALUE, "[1]"),
        shape("a push Try on a value-form handler of no result", () -> main(new Try(AT, new Comma(AT, c(1), raise()), nothing())), -1, 0, RunForm.PUSH, -1, 4, RunForm.VALUE, "[1]"),
        shape("a push Try on a value-form handler", () -> main(new Try(AT, new Comma(AT, raise(), c(5)), c(2))), -1, 0, RunForm.PUSH, -1, 4, RunForm.VALUE, "[2]"),
        shape(
            "a push Call, its closure breaking out, on a value-form body",
            () -> main(new Label(AT, 0, new Comma(AT, new Call(AT, 0, List.of(new Break(AT, 0)), List.of()), c(7))), new Function(AT, 1, 0, recursing(c(5), new Identity(AT)))),
            -1,
            2,
            RunForm.PUSH,
            0,
            0,
            RunForm.VALUE,
            "[5, 7]"),
        shape(
            "a push Call, its closure breaking out, on a value-form body of no result",
            () -> main(new Label(AT, 0, new Comma(AT, new Call(AT, 0, List.of(new Break(AT, 0)), List.of()), c(7))), new Function(AT, 1, 0, recursing(nothing(), new Identity(AT)))),
            -1,
            2,
            RunForm.PUSH,
            0,
            0,
            RunForm.VALUE,
            "[7]"),
        shape(
            "an AltBind on a value-form match of no result",
            () -> main(new AltBind(AT, c(1), 0, List.of(new Alternative(new Bind(AT, nothing(), 1, new Identity(AT)), List.of(1))), List.of(2), new Var(AT, 2))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            2,
            RunForm.VALUE,
            "[]"),
        shape("a value-form Label on a push body that runs out", () -> main(new Label(AT, 0, new If(AT, c(NO), new Break(AT, 0), nothing(), TRUTH))), -1, 0, RunForm.VALUE, -1, 1, RunForm.PUSH, "[]"),
        shape("a push Foreach on a value-form each of no result", () -> main(new Foreach(AT, new Comma(AT, c(1), c(2)), 0, nothing())), -1, 0, RunForm.PUSH, -1, 4, RunForm.VALUE, "[]"),
        shape(
            "a push Foreach on a value-form each",
            () -> main(new Foreach(AT, new Comma(AT, c(1), c(2)), 0, new Advance(AT, 0, new Identity(AT)))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            4,
            RunForm.VALUE,
            "[1, 2]"),
        shape(
            "a push Comma on a value-form FnN of no result",
            () -> main(new Comma(AT, new Intrinsic(AT, new Operation("nothingN", (FnN) (state, in, args) -> CallForm.NONE), List.of()), c(1))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[1]"),
        shape("a value-form Pipe on a left side of many results", () -> main(new Collect(AT, new Pipe(AT, new Comma(AT, c(1), c(2)), none(0)), LIST)), -1, 1, RunForm.VALUE, -1, 2, RunForm.PUSH, "[[]]"),
        shape("a value-form Bind on a value of many results", () -> main(new Collect(AT, new Bind(AT, new Comma(AT, c(1), c(2)), 0, none(1)), LIST)), -1, 1, RunForm.VALUE, -1, 2, RunForm.PUSH, "[[]]"),
        shape(
            "an AltBind on a value-form value of no result",
            () -> main(new AltBind(AT, nothing(), 0, List.of(new Alternative(new Bind(AT, new Var(AT, 0), 1, new Identity(AT)), List.of(1))), List.of(2), new Var(AT, 2))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[]"),
        shape(
            "a push Foreach on a value-form init",
            () -> main(new Foreach(AT, c(1), 0, new Comma(AT, new Advance(AT, 0, new Identity(AT)), c(9)))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[1, 9]"),
        shape(
            "a push Foreach on a value-form init of no result",
            () -> main(new Foreach(AT, nothing(), 0, new Comma(AT, new Advance(AT, 0, new Identity(AT)), c(9)))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[]"),
        shape(
            "a push Reduce, its each breaking out, on a value-form init of no result",
            () -> main(new Label(AT, 1, new Reduce(AT, nothing(), 0, new Comma(AT, new Advance(AT, 0, new Identity(AT)), new Break(AT, 1))))),
            -1,
            1,
            RunForm.PUSH,
            -1,
            2,
            RunForm.VALUE,
            "[]"),
        shape(
            "a push Reduce on an init of many results and a value-form each",
            () -> main(new Reduce(AT, new Comma(AT, c(1), c(2)), 0, new Advance(AT, 0, new Identity(AT)))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            4,
            RunForm.VALUE,
            "[1, 2]"),
        shape(
            "an AltBind on a value-form body of no result",
            () -> main(new AltBind(AT, c(1), 0, List.of(new Alternative(new Bind(AT, new Var(AT, 0), 1, new Identity(AT)), List.of(1))), List.of(2), nothing())),
            -1,
            0,
            RunForm.PUSH,
            -1,
            5,
            RunForm.VALUE,
            "[]"),
        shape("a value-form Foreach on an init of no result",() -> main(new Foreach(AT, nothing(), 0, new Advance(AT, 0, new Identity(AT)))), -1, 0, RunForm.VALUE, -1, 1, RunForm.VALUE, "[]"),
        shape(
            "a push Reduce, its each breaking out, on a value-form init",
            () -> main(new Label(AT, 1, new Reduce(AT, c(1), 0, new Comma(AT, new Advance(AT, 0, new Identity(AT)), new Break(AT, 1))))),
            -1,
            1,
            RunForm.PUSH,
            -1,
            2,
            RunForm.VALUE,
            "[]"),
        shape(
            "a ParamCall on a value-form closure",
            () -> main(new Call(AT, 0, List.of(c(5)), List.of()), new Function(AT, 1, 0, recursing(new ParamCall(AT, 0), new Pipe(AT, new ParamCall(AT, 0), new Identity(AT))))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[5]"),
        shape(
            "a ParamCall on a value-form closure of no result",
            () -> main(new Call(AT, 0, List.of(nothing()), List.of()), new Function(AT, 1, 0, recursing(new ParamCall(AT, 0), new Pipe(AT, new ParamCall(AT, 0), new Identity(AT))))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[]"),
        // "a" as $0 | "z" as $1 | f(. as $2 | $0): node 4 is main's Call, node 7 the closure's Var of main's slot 0, next
        // to main's "z" and the closure's own "yes". The reference binary 1.8.2 on "in": "a" as $a | "z" as $z |
        // def f(g): if . == "yes" then g else "yes" | f(g | .) end; f(. as $b | $a) gives "a"; with $z in place of $a
        // "z", with $b "yes".
        shape(
            "a ParamCall on a binding closure whose value-form Var reads the caller's slot",
            () ->
                main(
                    new Bind(AT, c("a"), 0, new Bind(AT, c("z"), 1, new Call(AT, 0, List.of(new Bind(AT, new Identity(AT), 2, new Var(AT, 0))), List.of()))),
                    new Function(AT, 1, 0, recursing(new ParamCall(AT, 0), new Pipe(AT, new ParamCall(AT, 0), new Identity(AT))))),
            -1,
            4,
            RunForm.PUSH,
            -1,
            7,
            RunForm.VALUE,
            "[a]"),
        shape(
            "a push Comma on a value-form Fn2",
            () -> main(new Comma(AT, new Intrinsic(AT, new Operation("first", (Fn2) (state, in, a, b) -> a), List.of(c(1), c(2))), c(3))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[1, 3]"),
        shape(
            "a push Comma on a value-form Fn2 of no result",
            () -> main(new Comma(AT, new Intrinsic(AT, new Operation("nothing2", (Fn2) (state, in, a, b) -> CallForm.NONE), List.of(c(1), c(2))), c(3))),
            -1,
            0,
            RunForm.PUSH,
            -1,
            1,
            RunForm.VALUE,
            "[3]"));
  }

  /**
   * Each shape runs in the optimised compile as in the conservative one (ADR-0047 #116, the eval contract): the same
   * results, answer, error, steps and bytes built, run to its end and with its sink stopping at the first result. The
   * pins first check that the optimised compile made no copy (ADR-0045 #117), so the freeze ids are the shape's own,
   * and that the node and its part run in the forms the shape names.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("shapes")
  void eachShapeRunsAsItsPushForm(Supplier<Program> program, int body, int id, RunForm form, int partBody, int part, RunForm partForm, String results) {
    Program compiled = program.get();
    Executable optimised = Compiler.compile(compiled, 0, CompileMode.OPTIMISED);
    assertThat(optimised.frozen().program()).as("premise: the program the optimised compile runs, no copy made").isEqualTo(compiled);
    assertThat(optimised.form(body, id)).as("the run form of node %d of body %d", id, body).isEqualTo(form);
    assertThat(optimised.form(partBody, part)).as("the run form of its part, node %d of body %d", part, partBody).isEqualTo(partForm);

    Outcome push = outcome(program.get(), CompileMode.CONSERVATIVE, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED);
    assertThat(push.results()).as("results under CONSERVATIVE (%s)", push).hasToString(results);
    assertThat(outcome(program.get(), CompileMode.OPTIMISED, UNLIMITED, NEVER_STOPS, UNLIMITED, UNLIMITED)).as("the optimised run to its end").isEqualTo(push);
    assertThat(outcome(program.get(), CompileMode.OPTIMISED, UNLIMITED, 1, UNLIMITED, UNLIMITED))
        .as("the optimised run stopping at its first result")
        .isEqualTo(outcome(program.get(), CompileMode.CONSERVATIVE, UNLIMITED, 1, UNLIMITED, UNLIMITED));
  }

  /** A Many of no result: the call form of an IntrinsicManyOp. */
  private static final Many EMPTY =
      new Many() {
        @Override
        public long first(RunState state, Object in, Object[] args) {
          return Many.END;
        }

        @Override
        public long next(RunState state, Object in, Object[] args, long cursor) {
          return Many.END;
        }

        @Override
        public Object value(RunState state, Object in, Object[] args, long cursor) {
          return in;
        }
      };

  static Stream<Arguments> neverEligible() {
    return Stream.of(
        Arguments.argumentSet("a Break", new BreakOp(0), "A Break has no value form"),
        Arguments.argumentSet("a ParamCall", new ParamCallOp(0, 0, 1, 1), "A ParamCall has no value form"),
        Arguments.argumentSet(
            "an AltBind",
            new AltBindOp(new IdentityOp(false), 0, new Op[] {new IdentityOp(false)}, new Argument[][] {{}}, new int[0], new IdentityOp(false)),
            "An AltBind has no value form"),
        Arguments.argumentSet("a Many intrinsic", new IntrinsicManyOp(EMPTY, new Argument[0]), "A Many intrinsic has no value form"));
  }

  /**
   * The kinds that are never eligible have no value form (ADR-0047 #116, the eval contract): eval on a Break, a
   * ParamCall, an AltBind or a Many intrinsic throws IllegalStateException, before it reads its slots or ticks.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("neverEligible")
  void theKindsThatAreNeverEligibleHaveNoValueForm(Op op, String message) {
    RunState state = new RunState();
    state.begin(UNLIMITED, UNLIMITED, UNLIMITED);
    assertThatThrownBy(() -> op.eval(state, new Object[1], "in")).as("eval of %s", op.getClass().getSimpleName()).isInstanceOf(IllegalStateException.class).hasMessage(message);
    assertThat(state.steps()).as("steps of the refused eval").isZero();
  }
}
