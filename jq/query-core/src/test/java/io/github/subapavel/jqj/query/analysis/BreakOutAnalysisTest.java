package io.github.subapavel.jqj.query.analysis;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn1;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * May break out at query-core's own seam (#115, ADR-0007 "Amendment (ticket #115)", GLOSSARY "May break out"), over
 * hand-built programs: a node may break out when its lowest target is below its own freeze id. A Break's target is the
 * Label that binds its slot in its body, or -1 when none there does; a ParamCall's is -1; a Call's is -1 when its callee
 * may break out and otherwise the lowest of its closures'; Identity, Const and Var have none; every other kind takes the
 * lowest of its parts. The fixpoint runs callee-first over the recursive groups, each from false, main last.
 *
 * <p>Seam (the design's): {@code Facts.mayBreakOut(int body, int id)}, with -1 for main and a function's place
 * otherwise, as {@link Facts#cardinality}. Not expressible here: "a group takes at most 2 rounds", since no seam counts
 * rounds and every count of rounds gives the same facts.
 */
class BreakOutAnalysisTest {
  private static final Source AT = new Source(0, 1);

  /** The slot of the Label each rule's node stands under; no rule's node names it otherwise. */
  private static final int OUTER = 9;

  private static Identity id() {
    return new Identity(AT);
  }

  private static Const c() {
    return new Const(AT, 1);
  }

  private static Comma comma(Node left, Node right) {
    return new Comma(AT, left, right);
  }

  private static Pipe pipe(Node left, Node right) {
    return new Pipe(AT, left, right);
  }

  private static If when(Node cond, Node then, Node otherwise) {
    return new If(AT, cond, then, otherwise, v -> true);
  }

  private static Label label(int slot, Node body) {
    return new Label(AT, slot, body);
  }

  private static Break brk(int slot) {
    return new Break(AT, slot);
  }

  /** A Call of {@code fn} with {@code closures} and no values. */
  private static Call call(int fn, Node... closures) {
    return new Call(AT, fn, List.of(closures), List.of());
  }

  /** A Call of {@code fn} with no closures and the one value {@code $slot}. */
  private static Call callWith(int fn, int slot) {
    return new Call(AT, fn, List.of(), List.of(new Var(AT, slot)));
  }

  private static Function function(int closures, Node body) {
    return new Function(AT, closures, 0, body);
  }

  /** A function of one value parameter, slot 0 of its body. */
  private static Function valued(Node body) {
    return new Function(AT, 0, 1, body);
  }

  /** A callee that takes one closure and never runs it: it cannot break out. */
  private static Function quietCallee() {
    return function(1, c());
  }

  /** A program analysed in the optimised compile: its numbering and its facts. */
  private record Analysed(Numbering numbering, Facts facts) {
    static Analysed of(Node main, Function... functions) {
      Program program = new Program(main, List.of(functions));
      Numbering numbering = Numbering.of(program);
      return new Analysed(numbering, Facts.optimised(numbering, CallGraph.of(program, numbering)));
    }

    /** The fact of {@code node} of the body {@code body}, -1 for the main tree. */
    boolean of(int body, Node node) {
      return facts.mayBreakOut(body, numbering.id(node));
    }
  }

  /** A node of one kind built around a part: the Break under test, or a Const in the quiet twin. */
  @FunctionalInterface
  interface Kind {
    Node around(Node part);
  }

  /** Each kind, its part at each place it may stand: the node takes the lowest target of its parts. */
  static Stream<Arguments> kinds() {
    return Stream.of(
        Arguments.argumentSet("a Pipe's left side", (Kind) p -> pipe(p, c())),
        Arguments.argumentSet("a Pipe's right side", (Kind) p -> pipe(c(), p)),
        Arguments.argumentSet("a Comma's left side", (Kind) p -> comma(p, c())),
        Arguments.argumentSet("a Comma's right side", (Kind) p -> comma(c(), p)),
        Arguments.argumentSet("a Bind's value", (Kind) p -> new Bind(AT, p, 0, c())),
        Arguments.argumentSet("a Bind's body", (Kind) p -> new Bind(AT, c(), 0, p)),
        Arguments.argumentSet("a Binds' later value", (Kind) p -> new Binds(AT, List.of(c(), p), List.of(0, 1), c())),
        Arguments.argumentSet("a Binds' body", (Kind) p -> new Binds(AT, List.of(c(), c()), List.of(0, 1), p)),
        Arguments.argumentSet("an If's condition", (Kind) p -> when(p, c(), c())),
        Arguments.argumentSet("an If's then branch", (Kind) p -> when(c(), p, c())),
        Arguments.argumentSet("an If's else branch", (Kind) p -> when(c(), c(), p)),
        Arguments.argumentSet("an Alt's left side", (Kind) p -> new Alt(AT, p, c(), v -> true)),
        Arguments.argumentSet("an Alt's right side", (Kind) p -> new Alt(AT, c(), p, v -> true)),
        Arguments.argumentSet("an AltBind's value", (Kind) p -> new AltBind(AT, p, 0, List.of(new Alternative(id(), List.of())), List.of(), id())),
        Arguments.argumentSet("an AltBind's match", (Kind) p -> new AltBind(AT, c(), 0, List.of(new Alternative(p, List.of())), List.of(), id())),
        Arguments.argumentSet("an AltBind's body: it catches no break", (Kind) p -> new AltBind(AT, c(), 0, List.of(new Alternative(id(), List.of())), List.of(), p)),
        Arguments.argumentSet("a Try's body: it catches no break", (Kind) p -> new Try(AT, p, c())),
        Arguments.argumentSet("a Try's handler", (Kind) p -> new Try(AT, c(), p)),
        Arguments.argumentSet("a Collect's body", (Kind) p -> new Collect(AT, p, (elements, length) -> null)),
        Arguments.argumentSet("a Reduce's init", (Kind) p -> new Reduce(AT, p, 0, new Advance(AT, 0, id()))),
        Arguments.argumentSet("a Reduce's each", (Kind) p -> new Reduce(AT, c(), 0, pipe(p, new Advance(AT, 0, id())))),
        Arguments.argumentSet("a Foreach's init", (Kind) p -> new Foreach(AT, p, 0, new Advance(AT, 0, id()))),
        Arguments.argumentSet("a Foreach's each", (Kind) p -> new Foreach(AT, c(), 0, pipe(p, new Advance(AT, 0, id())))),
        Arguments.argumentSet("an Advance's update", (Kind) p -> new Advance(AT, 0, p)),
        Arguments.argumentSet("a Label of another slot", (Kind) p -> label(8, p)),
        Arguments.argumentSet("a Call's closure, its callee quiet", (Kind) p -> call(0, p)));
  }

  /**
   * In {@code label $o | K(break $o)} the node K (id 1) may break out, the Break too, and the Label (id 0), which binds
   * the Break, does not: the lowest target, not the parts' facts joined. The quiet twin, {@code K(1)}, does not.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("kinds")
  void breakOutRules(Kind kind) {
    Break b = brk(OUTER);
    Node node = kind.around(b);
    Label l = label(OUTER, node);
    Analysed a = Analysed.of(l, quietCallee());

    assertThat(a.numbering().id(node)).as("the node's freeze id").isEqualTo(1);
    assertThat(a.of(-1, node)).as("the node, a part breaking to the Label above it").isTrue();
    assertThat(a.of(-1, b)).as("the Break").isTrue();
    assertThat(a.of(-1, l)).as("the Label that binds the Break").isFalse();

    Node quiet = kind.around(c());
    assertThat(Analysed.of(quiet, quietCallee()).of(-1, quiet)).as("the quiet twin").isFalse();
  }

  /** Identity, Const and Var have no target, and an Intrinsic of atomic arguments none either. */
  @Test
  void leavesHaveNoTarget() {
    Var v = new Var(AT, 0);
    Intrinsic op = new Intrinsic(AT, new Operation("one", (Fn1) (state, in, x) -> x), List.of(new Var(AT, 0)));
    Node main = new Bind(AT, c(), 0, comma(id(), comma(v, op)));
    Analysed a = Analysed.of(main);

    assertThat(a.numbering().nodes(-1)).as("main's nodes").hasSize(8).allSatisfy(n -> assertThat(a.of(-1, n)).as("%s", n.getClass().getSimpleName()).isFalse());
  }

  /** A Break whose own Label is its parent: the Break may break out of itself, the Label not. */
  @Test
  void aBreakToItsParentLabel() {
    Break b = brk(0);
    Label l = label(0, b);
    Analysed a = Analysed.of(l);

    assertThat(a.of(-1, b)).as("the Break").isTrue();
    assertThat(a.of(-1, l)).as("the Label").isFalse();
  }

  /**
   * The Break's target is the Label that binds its slot, not the nearest one: in {@code label $a | label $b | break $a}
   * the inner Label may break out; in {@code label $a | label $b | break $b} neither does.
   */
  @Test
  void aBreakTargetsTheLabelOfItsSlot() {
    Label inner = label(1, brk(0));
    Label outer = label(0, inner);
    Analysed a = Analysed.of(outer);
    assertThat(a.of(-1, inner)).as("the inner Label, the Break naming the outer").isTrue();
    assertThat(a.of(-1, outer)).as("the outer Label").isFalse();

    Label innerOwn = label(1, brk(1));
    Label outerOwn = label(0, innerOwn);
    Analysed own = Analysed.of(outerOwn);
    assertThat(own.of(-1, innerOwn)).as("the inner Label, the Break naming it").isFalse();
    assertThat(own.of(-1, outerOwn)).as("the outer Label").isFalse();
  }

  /**
   * A Break naming a value parameter (a token passed in): no Label of the function's body binds it, so its target is
   * -1, though main's Label binds the same slot number in main's body. The Call that passes the token may break out.
   */
  @Test
  void aBreakNamingAValueParameter() {
    Break b = brk(0);
    Function f = valued(b);
    Call outside = callWith(0, 0);
    Analysed a = Analysed.of(label(0, outside), f);

    assertThat(a.of(0, b)).as("f's Break, f's root").isTrue();
    assertThat(a.of(-1, outside)).as("main's Call of f").isTrue();
  }

  /** A function whose Break its own Label catches does not break out, nor does a Call of it. */
  @Test
  void aFunctionThatCatchesItsOwnBreak() {
    Function f = function(0, label(0, comma(c(), brk(0))));
    Call outside = call(0);
    Analysed a = Analysed.of(outside, f);

    assertThat(a.of(0, f.body())).as("f's root").isFalse();
    assertThat(a.of(-1, outside)).as("main's Call of f").isFalse();
  }

  /** A ParamCall's target is -1, whatever closure the call passes: it, its function and the Call of it break out. */
  @Test
  void aParamCallMayBreakOut() {
    ParamCall p = new ParamCall(AT, 0);
    Function f = function(1, pipe(c(), p));
    Call outside = call(0, c());
    Analysed a = Analysed.of(outside, f);

    assertThat(a.of(0, p)).as("the ParamCall").isTrue();
    assertThat(a.of(0, f.body())).as("f's root").isTrue();
    assertThat(a.of(-1, outside)).as("main's Call of f").isTrue();
  }

  /**
   * A Label in a Call's closure catches the closure's Break; the Call of a quiet callee then does not break out, and
   * neither does the Label. The closure's Break does.
   */
  @Test
  void aLabelInACallsClosure() {
    Break b = brk(0);
    Label inClosure = label(0, b);
    Call outside = call(0, inClosure);
    Analysed a = Analysed.of(outside, quietCallee());

    assertThat(a.of(-1, outside)).as("the Call").isFalse();
    assertThat(a.of(-1, inClosure)).as("the closure's Label").isFalse();
    assertThat(a.of(-1, b)).as("the closure's Break").isTrue();
  }

  /** A Call into an earlier group: fn1 calls fn0, which breaks through its value parameter; fn1 and main's Call too. */
  @Test
  void aCallIntoAnEarlierGroup() {
    Function breaks = valued(brk(0));
    Call inner = callWith(0, 0);
    Function caller = valued(when(c(), c(), inner));
    Call outside = callWith(1, 0);
    Analysed a = Analysed.of(label(0, outside), breaks, caller);

    assertThat(a.of(1, inner)).as("fn1's Call of fn0").isTrue();
    assertThat(a.of(1, caller.body())).as("fn1's root").isTrue();
    assertThat(a.of(-1, outside)).as("main's Call of fn1").isTrue();
  }

  /** A Call into a later place: fn0 calls fn1, which breaks; the callee's group is still done first. */
  @Test
  void aCallIntoALaterPlace() {
    Call inner = callWith(1, 0);
    Function caller = valued(when(c(), c(), inner));
    Function breaks = valued(brk(0));
    Analysed a = Analysed.of(label(0, callWith(0, 0)), caller, breaks);

    assertThat(a.of(0, inner)).as("fn0's Call of fn1").isTrue();
    assertThat(a.of(0, caller.body())).as("fn0's root").isTrue();
  }

  /**
   * A group of two, fn0 and fn1 calling each other, with one cause: whichever of them holds the Break, both roots may
   * break out. With the cause in fn0, fn1 is read before it in the group's first round (the groups close fn1 first), so
   * only the group's fixpoint gives fn1 its true.
   */
  @ParameterizedTest(name = "the Break in fn{0}")
  @MethodSource("causes")
  void aGroupOfTwoWithOneCause(int cause) {
    Function f0 = valued(when(c(), cause == 0 ? brk(0) : c(), callWith(1, 0)));
    Function f1 = valued(when(c(), cause == 1 ? brk(0) : c(), callWith(0, 0)));
    Analysed a = Analysed.of(label(0, callWith(0, 0)), f0, f1);

    assertThat(a.of(0, f0.body())).as("fn0's root").isTrue();
    assertThat(a.of(1, f1.body())).as("fn1's root").isTrue();
  }

  static Stream<Integer> causes() {
    return Stream.of(0, 1);
  }

  /** Each group starts from false: a group of two with no cause, and a function that only recurses, stay false. */
  @Test
  void aGroupWithNoCauseIsFalse() {
    Function f0 = function(0, when(c(), c(), call(1)));
    Function f1 = function(0, when(c(), c(), call(0)));
    Function only = function(0, call(2));
    Call outside = call(0);
    Call recurses = call(2);
    Analysed a = Analysed.of(comma(outside, recurses), f0, f1, only);

    assertThat(a.of(0, f0.body())).as("fn0's root").isFalse();
    assertThat(a.of(1, f1.body())).as("fn1's root").isFalse();
    assertThat(a.of(2, only.body())).as("fn2, which only recurses").isFalse();
    assertThat(a.of(-1, outside)).as("main's Call of fn0").isFalse();
    assertThat(a.of(-1, recurses)).as("main's Call of fn2").isFalse();
  }

  /** CONSERVATIVE stores true on every node of every body (ADR-0018); the optimised facts of the same program differ. */
  @Test
  void conservativeFactsAreTrue() {
    Node main = comma(c(), call(0));
    Program program = new Program(main, List.of(function(0, label(0, brk(0)))));
    Numbering numbering = Numbering.of(program);
    Facts conservative = Facts.conservative(numbering);
    Facts optimised = Facts.optimised(numbering, CallGraph.of(program, numbering));

    assertThat(numbering.nodes(-1)).hasSize(3).allSatisfy(n -> assertThat(conservative.mayBreakOut(-1, numbering.id(n))).isTrue());
    assertThat(numbering.nodes(0)).hasSize(2).allSatisfy(n -> assertThat(conservative.mayBreakOut(0, numbering.id(n))).isTrue());
    assertThat(optimised.mayBreakOut(-1, 0)).as("main's root under OPTIMISED").isFalse();
  }
}
