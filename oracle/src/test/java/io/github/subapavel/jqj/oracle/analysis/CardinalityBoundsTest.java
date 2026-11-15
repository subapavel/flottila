package io.github.subapavel.jqj.oracle.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JsonDoc;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.internal.parse.Resolver;
import io.github.subapavel.jqj.oracle.gen.CoreV1;
import io.github.subapavel.jqj.query.analysis.Cardinality;
import io.github.subapavel.jqj.query.analysis.Facts;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Program;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cardinality's guarantee is two-sided and checked against the interpreter (ADR-0007 "Cardinality" and its #112
 * amendment, F-56): over every core-v1 seed, each node n of main and of every function is watched by {@link BoundProbe}
 * and the program run in the conservative compile, while the fact read is the optimised compile's. Per activation of n:
 *
 * <ul>
 *   <li>NONE gives no result;
 *   <li>ONE gives at most one result, and exactly one when n ends normally; AT_MOST_ONE gives at most one;
 *   <li>after the result of a ONE or AT_MOST_ONE node, n gives no second result and raises nothing, and END comes one
 *       step after the result's emit returned: the Comma's step, the exact bound, so n ticks no step of its own (F-56);
 *   <li>no run ends by the step budget {@value #B}, far above every plain run (at most {@value #BASELINE_CAP} steps);
 *   <li>self-check: the watched run's results and error equal the plain conservative run's.
 * </ul>
 *
 * <p>Not watched, and counted: a node where only an atomic node may stand (an Intrinsic's argument, a Call's value) and
 * a link of an AltBind alternative's Bind chain above the leaf that reads the chain's slots, which the IR verifier
 * refuses, each refusal checked, so a probe it accepts there is a violation; and a bare ParamCall a Call passes on or
 * an Advance when the verifier refuses its probe (the design names both; at 9faa3bf it accepts them, and they are
 * watched and counted as such). Any other refusal is a failure of the probe. Every core-v1 seed must compile.
 *
 * <p>Seam (named in the #112 hand-back): {@code Executable.facts()} returning {@code query.analysis.Facts}, {@code
 * Facts.cardinality(int function, int id)} with -1 for main and a function's place otherwise, and {@code
 * Numbering.id(Node)}, a node's freeze id in its body, of the frozen program ({@code Executable.frozen()}, #117).
 */
class CardinalityBoundsTest {

  /** The step budget of every run (design: B = 10^6). */
  static final long B = 1_000_000;

  /**
   * The most steps a plain run may take, so a run-on past B cannot hide behind a long plain run: at 9faa3bf the largest
   * plain run over the core-v1 seeds takes 65 steps.
   */
  static final long BASELINE_CAP = 10_000;

  /**
   * Why a node may not be watched: the verifier must refuse a probe at an ATOMIC or MATCH place, and may refuse one at
   * the other two.
   */
  enum Skip {
    ATOMIC(true),
    MATCH(true),
    PASSED_PARAM_CALL(false),
    ADVANCE(false);

    /** Whether the verifier refuses every probe at such a place; a probe it accepts there is a violation. */
    final boolean refused;

    Skip(boolean refused) {
      this.refused = refused;
    }
  }

  /** Counts over the seeds this class ran, printed after the last one. */
  private static final Map<String, Long> COUNTS = new TreeMap<>();

  private static long largestBaseline;

  private static void count(String what) {
    COUNTS.merge(what, 1L, Long::sum);
  }

  @AfterAll
  static void report() {
    System.out.println("cardinalityBoundsHold: largest plain run " + largestBaseline + " steps; counts " + COUNTS);
  }

  static LongStream seeds() {
    return LongStream.range(CoreV1.FROM, CoreV1.TO);
  }

  @ParameterizedTest(name = "core-v1 seed {0}")
  @MethodSource("seeds")
  void cardinalityBoundsHold(long seed) {
    CoreV1.Generated g = CoreV1.generate(seed);
    String filter = g.args().getLast();
    String stdin = new String(g.stdin(), StandardCharsets.UTF_8);
    Program program;
    try {
      program = Resolver.resolve(Parser.parse(filter));
    } catch (JqCompileException e) {
      // Every core-v1 seed compiles; one that does not is a failure, never a skipped case (CODING_STANDARDS, Tests).
      throw new AssertionError("jqj does not compile core-v1 seed " + seed + ": " + filter, e);
    }
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    try (JsonDoc doc = workspace.read(MemorySegment.ofArray(g.stdin()))) {
      Object input = doc.root();
      BoundProbe.Ran plain = BoundProbe.run(Compiler.compile(program, 0, CompileMode.CONSERVATIVE), input, B);
      largestBaseline = Math.max(largestBaseline, plain.steps());
      count("seeds");
      assertThat(plain.steps()).as("steps of the plain run of %s on %s", filter, stdin).isLessThanOrEqualTo(BASELINE_CAP);

      // The optimised compile's facts number the program it froze, the specialised one (#117): its nodes are watched,
      // the originals and the copies, against the plain run of the program as resolved.
      Executable optimised = Compiler.compile(program, 0, CompileMode.OPTIMISED);
      Facts facts = optimised.facts();
      Program frozen = optimised.frozen().program();
      Numbering numbering = optimised.frozen().numbering();
      List<String> violations = new ArrayList<>();
      check(frozen, -1, frozen.main(), facts, numbering, input, plain.outcome(), violations);
      List<Function> functions = frozen.functions();
      for (int place = 0; place < functions.size(); place++) {
        check(frozen, place, functions.get(place).body(), facts, numbering, input, plain.outcome(), violations);
      }
      assertThat(violations).as("cardinality bounds of %s on %s", filter, stdin).isEmpty();
    }
  }

  /** Watches each node of the body {@code root} of {@code function} (-1 for main) and checks its fact's bounds. */
  private static void check(Program program, int function, Node root, Facts facts, Numbering numbering, Object input, BoundProbe.Outcome plain, List<String> violations) {
    Map<Node, Skip> skips = skips(root);
    for (Node n : BoundProbe.preOrder(root)) {
      int id = numbering.id(n);
      Cardinality card = facts.cardinality(function, id);
      String where = (function < 0 ? "main" : "fn" + function) + " " + id + " " + n.getClass().getSimpleName() + " card=" + card;
      if (card == Cardinality.NEVER) {
        violations.add(where + ": a fact keeps NEVER as NONE");
      }
      Skip skip = skips.get(n);
      BoundProbe probe = new BoundProbe();
      Executable watched;
      try {
        watched = Compiler.compile(probe.wrapped(program, function, n), 0, CompileMode.CONSERVATIVE);
      } catch (IllegalStateException e) {
        if (skip != null) {
          count("refused " + skip);
        } else {
          violations.add(where + ": the verifier refused the probe: " + e.getMessage());
        }
        continue;
      }
      if (skip != null && skip.refused) {
        violations.add(where + ": the verifier accepted the probe at a place this test skips as " + skip);
        continue;
      }
      if (skip != null) {
        count("watched " + skip);
      }
      count("watched " + card);
      BoundProbe.Ran ran = BoundProbe.run(watched, input, B);
      if (ran.outcome().ending() != null && ran.outcome().ending().startsWith("termination")) {
        violations.add(where + ": the watched run ended by " + ran.outcome().ending());
      }
      if (!ran.outcome().equals(plain)) {
        violations.add(where + ": the watched run gave " + ran.outcome() + ", the plain run " + plain);
      }
      for (BoundProbe.Activation a : probe.activations) {
        bounds(where, card, a, violations);
      }
    }
  }

  /** The bounds of {@code card} on one activation. */
  private static void bounds(String where, Cardinality card, BoundProbe.Activation a, List<String> violations) {
    switch (card) {
      case NONE -> {
        if (a.results > 0) {
          violations.add(where + ": gave " + a.results + " results");
        }
      }
      case ONE, AT_MOST_ONE -> {
        if (a.results > 1) {
          violations.add(where + ": gave " + a.results + " results");
        }
        if (card == Cardinality.ONE && a.end >= 0 && a.results != 1) {
          violations.add(where + ": ended normally after " + a.results + " results");
        }
        if (a.raisedAfterResult) {
          violations.add(where + ": raised after its result");
        }
        if (a.next >= 0 && a.end >= 0 && a.end - a.next - a.nestedEnds != 1) {
          violations.add(where + ": END came " + (a.end - a.next - a.nestedEnds) + " steps after its result's emit returned, besides " + a.nestedEnds + " of nested activations, not 1");
        }
      }
      default -> {
        // MANY bounds nothing.
      }
    }
  }

  /** The nodes of {@code root} the verifier refuses to watch, each with its reason. */
  private static Map<Node, Skip> skips(Node root) {
    Map<Node, Skip> skips = new IdentityHashMap<>();
    for (Node n : BoundProbe.preOrder(root)) {
      switch (n) {
        case Intrinsic i -> i.args().forEach(a -> skips.put(a, Skip.ATOMIC));
        case Call c -> {
          c.values().forEach(v -> skips.put(v, Skip.ATOMIC));
          c.closures().stream().filter(ParamCall.class::isInstance).forEach(p -> skips.put(p, Skip.PASSED_PARAM_CALL));
        }
        case Advance a -> skips.put(a, Skip.ADVANCE);
        case AltBind a -> {
          for (Alternative alternative : a.alternatives()) {
            // The Bind and Binds chain down to the match's leaf: watched, a link would end the chain above the leaf.
            Node link = alternative.match();
            while (link instanceof Bind || link instanceof Binds) {
              skips.put(link, Skip.MATCH);
              link = link instanceof Bind b ? b.body() : ((Binds) link).body();
            }
          }
        }
        default -> {
          // Every other node may be watched.
        }
      }
    }
    return skips;
  }

  /** The probe's own observations on runs whose behaviour the interpreter's step rules fix (ADR-0034, F-32, F-56). */
  @Test
  void boundsAreObservable() {
    // A ONE leaf: its result, then the Comma's one step to END.
    BoundProbe.Activation one = watchOnce("1,2", 1);
    assertThat(one.results).as("results of 1 in 1,2").isEqualTo(1);
    assertThat(one.end - one.next).as("steps from the result of 1 to END").isEqualTo(1);

    // A Comma after a result: a second result.
    BoundProbe.Activation comma = watchOnce("1,2", 0);
    assertThat(comma.results).as("results of 1,2").isEqualTo(2);
    assertThat(comma.resultAfterResult).as("a result after a result in 1,2").isTrue();

    // F-56's first probe: the error comes after the result.
    BoundProbe.Activation raising = watchOnce("1,error(\"x\")", 0);
    assertThat(raising.results).as("results of 1,error(\"x\")").isEqualTo(1);
    assertThat(raising.raisedAfterResult).as("an error after the result of 1,error(\"x\")").isTrue();
    assertThat(raising.end).as("END of 1,error(\"x\")").isEqualTo(-1);

    // One result, then a step of the node's own: its Comma enters empty.
    BoundProbe.Activation runOn = watchOnce("1,empty", 0);
    assertThat(runOn.results).as("results of 1,empty").isEqualTo(1);
    assertThat(runOn.end - runOn.next).as("steps from the result of 1,empty to END").isEqualTo(2);

    // A Break never ends normally; its Label does, with no result.
    BoundProbe.Activation brk = watchOnce("label $l|break $l", 1);
    assertThat(brk.results).as("results of the Break").isZero();
    assertThat(brk.end).as("END of the Break").isEqualTo(-1);
    BoundProbe.Activation label = watchOnce("label $l|break $l", 0);
    assertThat(label.results).as("results of the Label").isZero();
    assertThat(label.end).as("END of the Label").isNotNegative();

    // A recursive body (#117): its result comes from the innermost of five activations, null to 4. After the one
    // result's emit returned each inner activation's Comma stepped to its END, a step of the probe's, before the outer's.
    List<BoundProbe.Activation> nested = watchBody("def f: if . > 3 then . else . + 1 | f end; f", 0);
    assertThat(nested).as("activations of f's body on null").hasSize(5);
    for (int k = 0; k < nested.size(); k++) {
      BoundProbe.Activation a = nested.get(k);
      assertThat(a.results).as("results of activation %d", k).isEqualTo(1);
      assertThat(a.nestedEnds).as("nested activations ended after activation %d's result", k).isEqualTo(4 - k);
      assertThat(a.end - a.next).as("steps from activation %d's result to its END", k).isEqualTo(5 - k);
    }

    // A recursive body that enters itself after its first result: those activations are n's own steps, not nested ends.
    List<BoundProbe.Activation> after = watchBody("def f: if . > 1 then . else ., (. + 1 | f) end; f", 0);
    assertThat(after).as("activations of the MANY body on null").hasSize(3);
    for (int k = 0; k < after.size(); k++) {
      assertThat(after.get(k).nestedEnds).as("nested ends counted for activation %d, entered after its result", k).isZero();
    }
  }

  /** Watches the body of function {@code place} of {@code filter}, run on null, against the plain run: its activations. */
  private static List<BoundProbe.Activation> watchBody(String filter, int place) {
    Program program = Resolver.resolve(Parser.parse(filter));
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    try (JsonDoc doc = workspace.read(MemorySegment.ofArray("null".getBytes(StandardCharsets.UTF_8)))) {
      Object input = doc.root();
      BoundProbe.Ran plain = BoundProbe.run(Compiler.compile(program, 0, CompileMode.CONSERVATIVE), input, B);
      BoundProbe probe = new BoundProbe();
      BoundProbe.Ran ran = BoundProbe.run(Compiler.compile(probe.wrapped(program, place, program.functions().get(place).body()), 0, CompileMode.CONSERVATIVE), input, B);
      assertThat(ran.outcome()).as("the watched run of fn%d's body of %s", place, filter).isEqualTo(plain.outcome());
      return probe.activations;
    }
  }

  /**
   * Watches node {@code id} of main of {@code filter}, run on null; checks that the watched run equals the plain one and
   * returns its one activation.
   */
  private static BoundProbe.Activation watchOnce(String filter, int id) {
    Program program = Resolver.resolve(Parser.parse(filter));
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    try (JsonDoc doc = workspace.read(MemorySegment.ofArray("null".getBytes(StandardCharsets.UTF_8)))) {
      Object input = doc.root();
      BoundProbe.Ran plain = BoundProbe.run(Compiler.compile(program, 0, CompileMode.CONSERVATIVE), input, B);
      BoundProbe probe = new BoundProbe();
      Node n = BoundProbe.preOrder(program.main()).get(id);
      BoundProbe.Ran ran = BoundProbe.run(Compiler.compile(probe.wrapped(program, -1, n), 0, CompileMode.CONSERVATIVE), input, B);
      assertThat(ran.outcome()).as("the watched run of node %d of %s", id, filter).isEqualTo(plain.outcome());
      assertThat(probe.activations).as("activations of node %d of %s", id, filter).hasSize(1);
      return probe.activations.getFirst();
    }
  }
}
