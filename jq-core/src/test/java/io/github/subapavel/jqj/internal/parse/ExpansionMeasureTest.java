package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ResolverTest.calls;
import static io.github.subapavel.jqj.internal.parse.ResolverTest.parts;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Program;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The expansion measure (GLOSSARY "Expansion measure", ADR-0040 "Amendment (ticket #114)", F-90) bounds the lowering:
 * the Resolver inlines a call only if the nodes its expansion would spend fit what is left of the inline cap and the
 * levels it would reach fit {@code MAX_IR_DEPTH}, so for every inlined expansion the measured nodes are at least the
 * nodes spent and the measured levels at least the peak levels, and {@code spend()}'s backstop never fires. Seen at the
 * seam {@code Resolver.resolved(filter, parameters, spentBefore)} (package-private, the #114 design), which starts the
 * compile with {@code spentBefore} nodes of the cap spent:
 *
 * <ul>
 *   <li>the resolution is no Stub, so neither the backstop nor the IR depth Stub fired (the generated text has no
 *       construct but calls that could pass the bound);
 *   <li>the main tree and every function body are within 512 levels;
 *   <li>the program's nodes other than Calls are at most the text's nodes, each text lowered once, plus the {@code
 *       65 536 - spentBefore} left of the cap. A Call is left out of the count, so the bound holds whether or not a Call
 *       built inside an expansion spends.
 * </ul>
 *
 * <p>Each seed is one parameterised case (docs/agents/environment.md "Property tests"): it draws a filter of chained
 * definitions and a remaining budget. Seeds 0, 4, 8, … call the last definition again after its first call and call an
 * inner one from the main filter (earlier lifts: a lifted definition stays a Call); 1, 5, 9, … pass closures that no
 * body runs, each with a call in it (unused closure parameters, D1: the lifted form lowers each once); 2, 6, 10, … run
 * each closure parameter twice or three times (repeated closure parameters); 3, 7, 11, … call a self-recursive
 * definition, a recursive group that is always a Call. Seeds below 64 chain two calls a body, so the expansions double
 * past the cap; seeds from 64 chain one call a body over 400 to 999 definitions, every other body in brackets, with
 * closures that nest, so the expansions pass the IR depth or the 512th nested expansion. The budget spent before is 0 for a third of the seeds, within 4 096 of the cap
 * for a third, and anywhere in [0, 65 536] for the rest.
 */
class ExpansionMeasureTest {

  /** ADR-0040: {@code Resolver.INLINE_CAP}. */
  private static final int CAP = 65_536;

  /** ADR-0040 "IR depth": {@code Resolver.MAX_IR_DEPTH}. */
  private static final int MAX_IR_DEPTH = 512;

  /** A filter's text and the number of its nodes, as the Resolver lowers each text once. */
  private record Generated(String text, int nodes) {}

  /** A part of a body being written: its text and nodes. */
  private record Part(String text, int nodes) {
    static final Part DOT = new Part(".", 1);

    /** {@code (a <op> b)}: one node for the operator; the parentheses build none. */
    static Part of(Part a, String op, Part b) {
      return new Part("(" + a.text + " " + op + " " + b.text + ")", 1 + a.nodes + b.nodes);
    }

    /** {@code [a]}: one Collect. */
    static Part collect(Part a) {
      return new Part("[" + a.text + "]", 1 + a.nodes);
    }
  }

  static Stream<Arguments> seeds() {
    return LongStream.range(0, 128).mapToObj(Arguments::of);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void measureBoundsTheLowering(long seed) throws InterruptedException {
    SplittableRandom random = new SplittableRandom(seed);
    Generated filter = generate(seed, random);
    int third = random.nextInt(3);
    int spentBefore = third == 0 ? 0 : third == 1 ? CAP - random.nextInt(4_097) : random.nextInt(CAP + 1);
    String shown = "seed " + seed + " (" + filter.text().length() + " chars, " + filter.nodes() + " nodes), " + spentBefore + " nodes spent before";
    AtomicReference<Program> program = new AtomicReference<>();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    // A 16 MiB thread, as IrDepthTest's: an instrumented JVM needs more stack per level than a host's (F-44).
    Thread resolving =
        new Thread(
            null,
            () -> {
              try {
                program.set(Resolver.resolved(Parser.parse(filter.text()), List.of(), spentBefore).program());
              } catch (Throwable t) {
                thrown.set(t);
              }
            },
            "seed " + seed,
            16L * 1024 * 1024);
    resolving.start();
    resolving.join(20_000);
    assertThat(resolving.isAlive()).as("the resolution of %s still runs after 20 s", shown).isFalse();
    assertThat(thrown.get()).as("what resolving %s threw: no Stub, the backstop's and the IR depth's included", shown).isNull();
    assertThat(ResolverTest.levels(program.get().main())).as("levels of the main tree of %s", shown).isLessThanOrEqualTo(MAX_IR_DEPTH);
    int built = withoutCalls(program.get().main());
    for (Function f : program.get().functions()) {
      assertThat(ResolverTest.levels(f.body())).as("levels of a function body of %s", shown).isLessThanOrEqualTo(MAX_IR_DEPTH);
      built += withoutCalls(f.body());
    }
    assertThat(built).as("nodes other than Calls of the program of %s", shown).isLessThanOrEqualTo(filter.nodes() + CAP - spentBefore);
  }

  /**
   * The witness that the seam's budget bites, so the property cannot pass with the budget ignored: {@code def f:
   * [[.]]; f} spends three nodes. With 65 533 spent before, its expansion fits exactly and is inlined, a Collect and no
   * function; with 65 534, it would pass the cap and is a PLAIN Call to its one function. The measure is exact for a call
   * with no closure argument (the architect's ruling on nodes, #114).
   */
  @Test
  void aCallThatFitsTheBudgetLeftExactlyIsInlinedAndOneNodeLessLiftsIt() {
    String filter = "def f: [[.]]; f";
    Program fits = Resolver.resolved(Parser.parse(filter), List.of(), CAP - 3).program();
    assertThat(fits.main()).as("main tree of %s with %d spent before", filter, CAP - 3).isInstanceOf(Collect.class);
    assertThat(fits.functions()).as("functions of %s with %d spent before", filter, CAP - 3).isEmpty();
    Program passes = Resolver.resolved(Parser.parse(filter), List.of(), CAP - 2).program();
    assertThat(passes.main()).as("main tree of %s with %d spent before", filter, CAP - 2).isInstanceOf(Call.class);
    assertThat(passes.functions()).as("functions of %s with %d spent before", filter, CAP - 2).hasSize(1);
  }

  /**
   * A comma of many terms is a balanced Comma tree about log2 of its terms deep (F-44), so the measure gives it that
   * many levels, not one. {@code def w: 1,2,…,1024; def a1: . | w; def ai: . | a(i-1); …; [a500] | length}: inlined to
   * the bottom it would be 513 levels (the main filter's Pipe and Collect, a Pipe for each of the 500 definitions, w's
   * ten Comma levels and a leaf), so some call must be a PLAIN Call (ADR-0040 #114 amendment) and the filter runs. It
   * kills the mutant that measures the comma as one level (it inlines the whole chain, and the IR depth Stub fires).
   * jq-ref 1.8.2 gives 1024 (observed 2026-11-15, -nc -f, .logs/114-mut-jqref.log).
   */
  @Test
  void aWideCommaAtTheDepthEdgeLiftsACall() {
    StringBuilder w = new StringBuilder("def w: 1");
    for (int i = 2; i <= 1_024; i++) {
      w.append(',').append(i);
    }
    String filter = w.append("; ").append(chain("w", 500, "[a500] | length")).toString();
    Resolver.Resolved resolved = withinTheDepth(filter);
    assertThat(resolved.program().functions()).as("functions of the comma chain to a500: inlined, it is 513 levels").isNotEmpty();
    assertThat(ResolverTest.results(filter)).as("results of the comma chain to a500").containsExactly("1024");
  }

  /**
   * The measure's products saturate rather than wrap past 2^63. {@code def f0: .; def fi: f(i-1),f(i-1); …; def g1(p):
   * if . then p|p else 0 end; def gi(p): g(i-1)(p|p); …; g23(f40)}: f40's body measures 2^41 - 1 nodes, saturated, and
   * g23's body measures 2^24 - 2 runs of its parameter (two in g1, and in each g(i) two for each of g(i-1)'s plus two
   * for the lifted form's closure), so the inlined form of {@code g23(f40)} multiplies the two, past 2^63. With the
   * whole cap spent before (the seam), no call fits: {@code g23(f40)} is a PLAIN Call, no node is lowered inside an
   * expansion, and the program's nodes other than Calls are at most its text's 217 (f0's one, three in each of the 40
   * f bodies, six in g1's, four in each of the 22 other g bodies, two in the main filter). It kills the mutant whose
   * product wraps: the measure is negative, g23 is inlined and the backstop fires. jq-ref 1.8.2 gives 0 (observed
   * 2026-11-15, -nc -f, .logs/114-mut-jqref.log).
   */
  @Test
  void aMeasurePastTwoToTheSixtyThreeSaturates() {
    StringBuilder text = new StringBuilder("def f0: .; ");
    for (int i = 1; i <= 40; i++) {
      text.append("def f").append(i).append(": f").append(i - 1).append(",f").append(i - 1).append("; ");
    }
    text.append("def g1(p): if . then p|p else 0 end; ");
    for (int i = 2; i <= 23; i++) {
      text.append("def g").append(i).append("(p): g").append(i - 1).append("(p|p); ");
    }
    String filter = text.append("g23(f40)").toString();
    Program program = Resolver.resolved(Parser.parse(filter), List.of(), CAP).program();
    assertThat(program.main()).as("main tree of g23(f40) with the whole cap spent before").isInstanceOf(Call.class);
    int built = withoutCalls(program.main());
    for (Function f : program.functions()) {
      built += withoutCalls(f.body());
    }
    assertThat(built).as("nodes other than Calls of g23(f40) with the whole cap spent before").isLessThanOrEqualTo(217);
    assertThat(ResolverTest.results(filter)).as("results of g23(f40)").containsExactly("0");
  }

  /**
   * The measure is computed over the live definitions only ({@link Binder#calleeFirst()}): jq drops a definition the
   * main filter does not reach, so a call in it that binds nowhere is no error, and the Binder leaves it unbound.
   * jq-ref 1.8.2 gives 1 for {@code def dead: nosuch; 1} and 2 for {@code def dead: nosuch(1; 2); def live: 2; live}
   * (observed 2026-11-15, -nc, .logs/114-mut-jqref-dead.log). It kills the mutant that puts dead definitions in
   * calleeFirst(): the measure walks the dead body and finds no binding for {@code nosuch}.
   */
  @Test
  void aDeadDefinitionWithAnUnboundCallIsNotMeasured() {
    assertThat(ResolverTest.results("def dead: nosuch; 1")).as("results of def dead: nosuch; 1").containsExactly("1");
    assertThat(ResolverTest.results("def dead: nosuch(1; 2); def live: 2; live")).as("results of def dead: nosuch(1; 2); def live: 2; live").containsExactly("2");
    ParsedFilter parsed = Parser.parse("def dead: nosuch(1; 2); def live: 2; live");
    List<String> measured = new ArrayList<>();
    for (Ast.FuncDef def : Binder.bind(parsed.main(), List.of(), parsed.text()).calleeFirst()) {
      measured.add(def.name());
    }
    assertThat(measured).as("calleeFirst() of def dead: nosuch(1; 2); def live: 2; live").containsExactly("live");
  }

  static Stream<Arguments> valueBinds() {
    return Stream.of(
        Arguments.of("a body four levels deep", "def v($a; $b; $c): [[[.]]]; ", "v(1; 2; 3)", 506, "[[[null]]]"),
        Arguments.of("a closure run four levels deep", "def v($a; $b; $c; f): [[[[f]]]]; ", "v(1; 2; 3; [[[[.]]]])", 501, "[[[[[[[[null]]]]]]]]"));
  }

  /**
   * An inlined body sits below its value parameters' Binds, a level each (ADR-0043), and so does each closure it runs.
   * {@code def v($a; $b; $c): [[[.]]]; def a1: . | v(1; 2; 3); def ai: . | a(i-1); …; a506}: inlined to the bottom it
   * would be 506 + 3 + 4 = 513 levels; with {@code def v($a; $b; $c; f): [[[[f]]]]} and {@code v(1; 2; 3; [[[[.]]]])},
   * a chain to a501 would be 501 + 3 + 4 + 5 = 513. Some call must be a PLAIN Call and the filter runs. It kills the
   * mutants that leave the Binds out of the inlined form's reach, for the body (first row) or for a closure it runs
   * (second row), where the lifted form's reach is below either: the chain is inlined and the IR depth Stub fires.
   * jq-ref 1.8.2 gives {@code [[[null]]]} and {@code [[[[[[[[null]]]]]]]]} (observed 2026-11-15, -nc -f,
   * .logs/114-mut-jqref.log).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("valueBinds")
  void valueBindsAroundAnInlinedBodyCountTowardTheDepth(String shown, String definition, String call, int chain, String expected) {
    String filter = definition + chain(call, chain, "a" + chain);
    Resolver.Resolved resolved = withinTheDepth(filter);
    assertThat(resolved.program().functions()).as("functions of the chain to a%d over %s: inlined, it is 513 levels", chain, shown).isNotEmpty();
    assertThat(ResolverTest.results(filter)).as("results of the chain to a%d over %s", chain, shown).containsExactly(expected);
  }

  /**
   * The k-th value parameter's argument sits below k Binds around a Call of a recursive definition (ADR-0045 #107), so
   * its levels count from k levels down. {@code def r($a; $b): if $a > 0 then r($a - 1; $b) else $b end; def a1: . |
   * r(1; [[[[[.]]]]]); def ai: . | a(i-1); …; a505}: inlined to the bottom it would be 505 + 2 + 6 = 513 levels (a Pipe
   * for each definition, the two Binds, and the second argument's five Collects and its leaf), so a definition of the
   * chain must be a PLAIN Call too and the filter runs. It kills the mutant that puts every value argument of the lifted
   * form one level down: the chain is inlined and the IR depth Stub fires. jq-ref 1.8.2 gives {@code [[[[[null]]]]]}
   * (observed 2026-11-15, -nc -f, .logs/114-mut-jqref.log).
   */
  @Test
  void theSecondValueArgumentOfARecursiveCallIsTwoBindsDown() {
    String filter = "def r($a; $b): if $a > 0 then r($a - 1; $b) else $b end; " + chain("r(1; [[[[[.]]]]])", 505, "a505");
    Resolver.Resolved resolved = withinTheDepth(filter);
    assertThat(resolved.names()).as("functions of the chain to a505 over r: inlined, it is 513 levels").anyMatch(name -> name.startsWith("a"));
    assertThat(ResolverTest.results(filter)).as("results of the chain to a505 over r").containsExactly("[[[[[null]]]]]");
  }

  /** {@code def a1: . | first; def ai: . | a(i-1); …; def an: . | a(n-1); main}: a Pipe a definition. */
  private static String chain(String first, int n, String main) {
    StringBuilder text = new StringBuilder("def a1: . | ").append(first).append("; ");
    for (int i = 2; i <= n; i++) {
      text.append("def a").append(i).append(": . | a").append(i - 1).append("; ");
    }
    return text.append(main).toString();
  }

  /** The resolution of {@code filter}, no Stub, with its main tree and every function body within 512 levels. */
  private static Resolver.Resolved withinTheDepth(String filter) {
    Resolver.Resolved resolved = Resolver.resolved(Parser.parse(filter), List.of());
    assertThat(ResolverTest.levels(resolved.program().main())).as("levels of the main tree").isLessThanOrEqualTo(MAX_IR_DEPTH);
    for (Function f : resolved.program().functions()) {
      assertThat(ResolverTest.levels(f.body())).as("levels of a function body").isLessThanOrEqualTo(MAX_IR_DEPTH);
    }
    return resolved;
  }

  /** The nodes of {@code node}'s tree but its Calls, by a loop. */
  private static int withoutCalls(Node node) {
    int count = 0;
    List<Node> pending = new ArrayList<>(List.of(node));
    while (!pending.isEmpty()) {
      Node next = pending.removeLast();
      if (!(next instanceof Call)) {
        count++;
      }
      pending.addAll(parts(next));
    }
    assertThat(count + calls(node).size()).as("the walk of the tree counts each node once").isEqualTo(ResolverTest.nodes(node));
    return count;
  }

  /** The filter of {@code seed}: {@code def d1…; def dn…;}, each body calling earlier ones, then the main filter. */
  private static Generated generate(long seed, SplittableRandom random) {
    int feature = (int) (seed % 4);
    boolean wide = seed < 64;
    int n = wide ? 6 + random.nextInt(13) : 400 + random.nextInt(600);
    int[] arity = new int[n + 1];
    StringBuilder text = new StringBuilder();
    int nodes = 0;
    if (feature == 3) {
      // A recursive group of one, always a Call: If, its condition, its then, the call and its closure (f|.).
      text.append("def r(f): if . then f else r(f|.) end; ");
      nodes += 7;
    }
    for (int i = 1; i <= n; i++) {
      arity[i] = feature == 1 || feature == 2 ? 1 + random.nextInt(2) : random.nextInt(3);
      StringBuilder head = new StringBuilder("def d").append(i);
      if (arity[i] > 0) {
        head.append("(p1").append(arity[i] == 2 ? "; p2" : "").append(')');
      }
      Part body;
      if (i == 1) {
        body = leaf(feature, arity[1], random);
      } else if (wide) {
        Part left = call(i - 1 - random.nextInt(Math.min(2, i - 1)), arity, feature, arity[i], random);
        Part right = call(i - 1, arity, feature, arity[i], random);
        body = Part.of(left, random.nextBoolean() ? "," : "|", right);
      } else {
        body = call(i - 1, arity, feature, arity[i], random);
        if (random.nextBoolean()) {
          body = Part.collect(body);
        }
      }
      if (feature == 2 && arity[i] > 0) {
        Part p = new Part("p1", 1);
        body = Part.of(body, ",", random.nextBoolean() ? Part.of(p, "|", p) : Part.of(Part.of(p, ",", p), ",", p));
      }
      if (feature == 3 && random.nextInt(4) == 0) {
        Part closure = leaf(feature, arity[i], random);
        body = Part.of(body, ",", new Part("r(" + closure.text + ")", 1 + closure.nodes));
      }
      text.append(head).append(": ").append(body.text).append("; ");
      nodes += body.nodes;
    }
    Part main = call(n, arity, feature, 0, random);
    if (feature == 0) {
      main = Part.of(Part.of(main, ",", call(n, arity, feature, 0, random)), ",", call(1 + random.nextInt(n), arity, feature, 0, random));
    }
    text.append(main.text);
    nodes += main.nodes;
    return new Generated(text.toString(), nodes);
  }

  /**
   * A call of definition {@code j} from a body with {@code params} closure parameters: one node, and an argument for
   * each of j's parameters. An argument of an unused parameter (feature 1) holds a call of an earlier definition, so its
   * lifted form lowers that call's expansion once.
   */
  private static Part call(int j, int[] arity, int feature, int params, SplittableRandom random) {
    if (arity[j] == 0) {
      return new Part("d" + j, 1);
    }
    StringBuilder text = new StringBuilder("d").append(j).append('(');
    int nodes = 1;
    for (int k = 0; k < arity[j]; k++) {
      Part arg;
      if (feature == 1 && j > 1 && arity[j - 1] == 0) {
        arg = Part.of(new Part("d" + (j - 1), 1), ",", leaf(feature, params, random));
      } else {
        arg = leaf(feature, params, random);
        if (random.nextBoolean()) {
          arg = Part.of(arg, "|", Part.DOT);
        }
      }
      text.append(k == 0 ? "" : "; ").append(arg.text);
      nodes += arg.nodes;
    }
    return new Part(text.append(')').toString(), nodes);
  }

  /**
   * A body or argument without calls: {@code .}, a closure parameter of the body it is in (never under feature 1, whose
   * bodies run no parameter), or one of them in brackets.
   */
  private static Part leaf(int feature, int params, SplittableRandom random) {
    Part leaf = params > 0 && feature != 1 && random.nextBoolean() ? new Part("p" + (1 + random.nextInt(params)), 1) : Part.DOT;
    return random.nextInt(3) == 0 ? Part.collect(leaf) : leaf;
  }
}
