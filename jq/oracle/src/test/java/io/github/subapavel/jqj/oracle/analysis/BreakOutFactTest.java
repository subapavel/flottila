package io.github.subapavel.jqj.oracle.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JsonDoc;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.internal.parse.Resolver;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.gen.CoreV1;
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
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * May break out's "false" is checked against the interpreter (#115 design, "breakOutFactHolds"; ADR-0007 "Amendment
 * (ticket #115)"). Over every core-v1 seed and every {@code label} filter of {@code diff/calls.cases} and {@code
 * diff/control.cases}, each node n of main and of every function the optimised compile marks false is watched as
 * {@code Bind(ENTER -> t, Comma(Try(Pipe(Collect(n), MARK(t)), RAISED(t)), END(t))) | .[]} and the program run in the
 * conservative compile with the step budget {@value #B}. A run of n that does not break out ends normally, so its
 * Collect gives MARK its array, or raises, which RAISED notes (a Try catches no break, ADR-0008): every ENTER must be
 * followed by MARK or RAISED, unless the run ends by a termination. Runs that end by the step budget are inconclusive and
 * counted.
 *
 * <p>Not watched, and counted: the places where only an atomic node may stand, an AltBind match's Bind chain, a bare
 * ParamCall a Call passes on and an Advance, where the verifier may refuse the probe (CardinalityBoundsTest). Any
 * other refusal is a failure. A {@code label} filter jqj does not compile has nothing to watch and is counted; every
 * core-v1 seed must compile.
 *
 * <p>Seam (the design's): {@code Executable.facts()} and {@code Facts.mayBreakOut(int body, int id)}, -1 for main, by
 * the freeze ids of the frozen program ({@code Executable.frozen()}, #117).
 */
class BreakOutFactTest {

  /** The step budget of every run (design: B = 10^6). */
  static final long B = 1_000_000;

  private static final Map<String, Long> COUNTS = new TreeMap<>();

  private static void count(String what) {
    COUNTS.merge(what, 1L, Long::sum);
  }

  @AfterAll
  static void report() {
    System.out.println("breakOutFactHolds: counts " + COUNTS);
  }

  /** One watched activation: what MARK and RAISED saw of it. */
  static final class Activation {
    boolean marked;
    boolean raised;
  }

  /** The probe of one watched node: its four operations and the activations ENTER made. */
  static final class Probe {
    final List<Activation> activations = new ArrayList<>();

    private final Operation enter =
        new Operation(
            "_test_enter",
            (Fn0)
                (state, in) -> {
                  Activation a = new Activation();
                  activations.add(a);
                  return a;
                });

    private final Operation mark =
        new Operation(
            "_test_mark",
            (Fn1)
                (state, in, token) -> {
                  ((Activation) token).marked = true;
                  return in;
                });

    private final Operation raised =
        new Operation(
            "_test_raised",
            (Fn1)
                (state, in, token) -> {
                  ((Activation) token).raised = true;
                  throw new FilterException(in);
                });

    private final Operation end = new Operation("_test_end", (Fn1) (state, in, token) -> CallForm.NONE);

    /** {@code n} watched through the token in slot {@code t}, collected and given on element by element. */
    Node wrap(Node n, int t) {
      Source s = n.source();
      // A fresh [.[]] of jq-core's own: its collector builds the array, and its .[] gives the elements on.
      Collect collected = (Collect) Resolver.resolve(Parser.parse("[.[]]")).main();
      Node marked = new Pipe(s, new Collect(s, n, collected.collector()), new Intrinsic(s, mark, List.of(new Var(s, t))));
      Node body = new Comma(s, new Try(s, marked, new Intrinsic(s, raised, List.of(new Var(s, t)))), new Intrinsic(s, end, List.of(new Var(s, t))));
      return new Pipe(s, new Bind(s, new Intrinsic(s, enter, List.of()), t, body), collected.body());
    }

    /** {@code program} with the node {@code n} of the body {@code function} (-1 for main) watched. */
    Program wrapped(Program program, int function, Node n) {
      if (function < 0) {
        Node main = program.main();
        return new Program(BoundProbe.replace(main, n, wrap(n, BoundProbe.freshSlot(main, 0))), program.functions());
      }
      List<Function> functions = new ArrayList<>(program.functions());
      Function f = functions.get(function);
      Node body = BoundProbe.replace(f.body(), n, wrap(n, BoundProbe.freshSlot(f.body(), f.values())));
      functions.set(function, new Function(f.source(), f.closures(), f.values(), body));
      return new Program(program.main(), functions);
    }
  }

  private static Path diff() {
    try {
      return Path.of(BreakOutFactTest.class.getResource("/diff").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The inputs: each core-v1 seed, then each {@code label} filter of the calls and control cases, with its stdin. */
  static Stream<Arguments> inputs() {
    Stream<Arguments> seeds =
        LongStream.range(CoreV1.FROM, CoreV1.TO)
            .mapToObj(
                seed -> {
                  CoreV1.Generated g = CoreV1.generate(seed);
                  return Arguments.argumentSet("core-v1 seed " + seed, g.args().getLast(), g.stdin(), true);
                });
    List<Arguments> labels = new ArrayList<>();
    for (String family : List.of("calls.cases", "control.cases")) {
      for (DiffCase c : DiffCase.read(diff().resolve(family))) {
        String filter = c.args().getLast();
        if (filter.contains("label")) {
          labels.add(Arguments.argumentSet(c.id(), filter, c.stdin(), false));
        }
      }
    }
    assertThat(labels).as("label filters of the calls and control cases").hasSizeGreaterThan(20);
    return Stream.concat(seeds, labels.stream());
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("inputs")
  void breakOutFactHolds(String filter, byte[] stdin, boolean mustCompile) {
    Program program;
    try {
      program = Resolver.resolve(Parser.parse(filter));
    } catch (JqCompileException e) {
      if (mustCompile) {
        throw new AssertionError("jqj does not compile " + filter, e);
      }
      count("label filters jqj does not compile");
      return;
    }
    // The optimised compile's facts number the program it froze, the specialised one (#117): its nodes are watched,
    // the originals and the copies.
    Executable optimised = Compiler.compile(program, 0, CompileMode.OPTIMISED);
    Facts facts = optimised.facts();
    Program frozen = optimised.frozen().program();
    Numbering numbering = optimised.frozen().numbering();
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    try (JsonDoc doc = workspace.read(MemorySegment.ofArray(firstDocument(stdin)))) {
      Object input = doc.root();
      List<String> violations = new ArrayList<>();
      check(frozen, -1, frozen.main(), facts, numbering, input, violations);
      for (int place = 0; place < frozen.functions().size(); place++) {
        check(frozen, place, frozen.functions().get(place).body(), facts, numbering, input, violations);
      }
      assertThat(violations).as("nodes marked false that broke out, in %s", filter).isEmpty();
    }
  }

  /** The first line of {@code stdin}, the one document a run reads; {@code null} without stdin. */
  private static byte[] firstDocument(byte[] stdin) {
    if (stdin == null || stdin.length == 0) {
      return "null".getBytes(StandardCharsets.UTF_8);
    }
    int lf = 0;
    while (lf < stdin.length && stdin[lf] != '\n') {
      lf++;
    }
    return Arrays.copyOf(stdin, lf);
  }

  private static void check(Program program, int function, Node root, Facts facts, Numbering numbering, Object input, List<String> violations) {
    Map<Node, String> skips = skips(root);
    for (Node n : BoundProbe.preOrder(root)) {
      int id = numbering.id(n);
      if (facts.mayBreakOut(function, id)) {
        count("marked true");
        continue;
      }
      String where = (function < 0 ? "main" : "fn" + function) + " " + id + " " + n.getClass().getSimpleName();
      Probe probe = new Probe();
      Executable watched;
      try {
        watched = Compiler.compile(probe.wrapped(program, function, n), 0, CompileMode.CONSERVATIVE);
      } catch (IllegalStateException e) {
        if (skips.containsKey(n)) {
          count("refused " + skips.get(n));
        } else {
          violations.add(where + ": the verifier refused the probe: " + e.getMessage());
        }
        continue;
      }
      count("watched false");
      BoundProbe.Ran ran = BoundProbe.run(watched, input, B);
      if (ran.outcome().ending() != null && ran.outcome().ending().startsWith("termination")) {
        count("inconclusive: " + ran.outcome().ending());
        continue;
      }
      for (Activation a : probe.activations) {
        if (!a.marked && !a.raised) {
          violations.add(where + ": an activation ended with no MARK and no RAISED, so it broke out");
        }
      }
    }
  }

  /** The nodes of {@code root} where the verifier may refuse a probe, each with its reason. */
  private static Map<Node, String> skips(Node root) {
    Map<Node, String> skips = new IdentityHashMap<>();
    for (Node n : BoundProbe.preOrder(root)) {
      switch (n) {
        case Intrinsic i -> i.args().forEach(a -> skips.put(a, "ATOMIC"));
        case Call c -> {
          c.values().forEach(v -> skips.put(v, "ATOMIC"));
          c.closures().stream().filter(ParamCall.class::isInstance).forEach(p -> skips.put(p, "PASSED_PARAM_CALL"));
        }
        case Advance a -> skips.put(a, "ADVANCE");
        case AltBind a -> {
          for (Alternative alternative : a.alternatives()) {
            Node link = alternative.match();
            while (link instanceof Bind || link instanceof Binds) {
              skips.put(link, "MATCH");
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

  /**
   * Witness that the probe sees a break-out: in {@code label $l | (1, break $l)} the Comma (main 1), which the optimised
   * compile marks true, breaks out of its watch, while the Const 1 (main 2), marked false, is marked.
   */
  @Test
  void theProbeSeesABreakOut() {
    String filter = "label $l | (1, break $l)";
    // The facts number the frozen program (#117), whose nodes are watched.
    Executable optimised = Compiler.compile(Resolver.resolve(Parser.parse(filter)), 0, CompileMode.OPTIMISED);
    Facts facts = optimised.facts();
    Program program = optimised.frozen().program();
    List<Node> main = BoundProbe.preOrder(program.main());
    assertThat(main.get(1)).as("main 1 of %s", filter).isInstanceOf(Comma.class);
    assertThat(facts.mayBreakOut(-1, 1)).as("the Comma's fact").isTrue();
    assertThat(facts.mayBreakOut(-1, 2)).as("the Const's fact").isFalse();

    Workspace workspace = JqEngine.builder().build().newWorkspace();
    try (JsonDoc doc = workspace.read(MemorySegment.ofArray("null".getBytes(StandardCharsets.UTF_8)))) {
      Probe comma = new Probe();
      BoundProbe.Ran ran = BoundProbe.run(Compiler.compile(comma.wrapped(program, -1, main.get(1)), 0, CompileMode.CONSERVATIVE), doc.root(), B);
      assertThat(ran.outcome().ending()).as("ending of the watched Comma").isNull();
      assertThat(comma.activations).as("activations of the Comma").hasSize(1);
      assertThat(comma.activations.getFirst().marked || comma.activations.getFirst().raised).as("MARK or RAISED after the Comma's ENTER").isFalse();

      Probe one = new Probe();
      BoundProbe.run(Compiler.compile(one.wrapped(program, -1, main.get(2)), 0, CompileMode.CONSERVATIVE), doc.root(), B);
      assertThat(one.activations).as("activations of the Const").hasSize(1);
      assertThat(one.activations.getFirst().marked).as("MARK after the Const's ENTER").isTrue();
    }
  }
}
