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
