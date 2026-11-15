package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.Specialising.DEC;
import static io.github.subapavel.jqj.query.compile.Specialising.LESS;
import static io.github.subapavel.jqj.query.compile.Specialising.LIST;
import static io.github.subapavel.jqj.query.compile.Specialising.PLUS;
import static io.github.subapavel.jqj.query.compile.Specialising.TAG;
import static io.github.subapavel.jqj.query.compile.Specialising.TRUTH;
import static io.github.subapavel.jqj.query.compile.Specialising.at;
import static io.github.subapavel.jqj.query.compile.Specialising.countdown;
import static io.github.subapavel.jqj.query.compile.Specialising.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.Specialising.N;
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
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The specialisation pass over every node kind (#117, ADR-0045 "Amendment (ticket #117)"): a recursive function whose
 * body holds each kind around a ParamCall of its invariant parameter, called from main with an argument that holds
 * each kind too, reads a free slot and breaks to a Label around the call. The copy runs as the original does under
 * CONSERVATIVE, its slots, Labels, loops and alternatives renamed, and {@code specialised()} is the nodes it holds. A
 * compile in which no call runs a copy keeps its program object, unrebuilt.
 */
class SpecialiserKindsTest {
  private static final Object IN = new N(2);

  /** Hands out slots from a start, each once. */
  private static final class Slots {
    private int next;

    Slots(int first) {
      next = first;
    }

    int take() {
      return next++;
    }
  }

  private static Node plus(Node a, Node b) {
    return new Intrinsic(at(0), PLUS, List.of(a, b));
  }

  /**
   * {@code [try ((leaf | .) // 0), ((. as $a | $a, 1 as $b, $c | $b + $c)), (. as $v ?// . | $grp), (label $l | if . then
   * break $l else . end), reduce 1 as $x (.; . + $x), foreach 1 as $y (.; . + $y)]}, each slot taken from {@code s}.
   */
  private static Node everyKind(Node leaf, Slots s) {
    Node tried = new Try(at(1), new Alt(at(2), new Pipe(at(3), leaf, new Identity(at(3))), new Const(at(4), new N(0)), TRUTH), new Identity(at(5)));
    int a = s.take();
    int b = s.take();
    int c = s.take();
    Node binds = new Bind(at(6), new Identity(at(6)), a, new Binds(at(7), List.of(new Var(at(7), a), new Const(at(7), new N(1))), List.of(b, c), plus(new Var(at(8), b), new Var(at(8), c))));
    int v = s.take();
    int own = s.take();
    int group = s.take();
    Alternative first = new Alternative(new Bind(at(9), new Var(at(9), v), own, new Identity(at(9))), List.of(own));
    Alternative second = new Alternative(new Identity(at(10)), List.of(Alternative.NULL));
    Node altBind = new AltBind(at(11), new Identity(at(11)), v, List.of(first, second), List.of(group), new Var(at(12), group));
    int l = s.take();
    Node label = new Label(at(13), l, new If(at(14), new Identity(at(14)), new Break(at(15), l), new Identity(at(16)), TRUTH));
    int st = s.take();
    int x = s.take();
    Node reduce = new Reduce(at(17), new Identity(at(17)), st, new Bind(at(18), new Const(at(18), new N(1)), x, new Advance(at(19), st, plus(new Identity(at(19)), new Var(at(19), x)))));
    int fst = s.take();
    int y = s.take();
    Node foreach = new Foreach(at(20), new Identity(at(20)), fst, new Bind(at(21), new Const(at(21), new N(1)), y, new Advance(at(22), fst, plus(new Identity(at(22)), new Var(at(22), y)))));
    Node all = new Comma(at(23), tried, new Comma(at(24), binds, new Comma(at(25), altBind, new Comma(at(26), label, new Comma(at(27), reduce, foreach)))));
    return new Collect(at(28), all, LIST);
  }

  /**
   * {@code def k(f; h; $m): if . then everyKind(f), (h | . + $m), (dec | k(f; h | tag; $m)) else . end}: f is invariant,
   * h is not, and $m is slot 0. Main: {@code 1 as $n | label $out | k(everyKind(. + $n), if . < 1 then break $out
   * else . end; tag; 3)}, the argument reading the free slot $n and breaking to the free label $out.
   */
  private static Program program() {
    Slots body = new Slots(1);
    Node again =
        new Pipe(
            at(30),
            new Intrinsic(at(30), DEC, List.of()),
            new Call(at(31), 0, List.of(new ParamCall(at(31), 0), new Pipe(at(31), new ParamCall(at(31), 1), new Intrinsic(at(31), TAG, List.of(new Identity(at(31)))))), List.of(new Var(at(31), 0))));
    Node uses = new Comma(at(32), everyKind(new ParamCall(at(32), 0), body), new Comma(at(33), new Pipe(at(33), new ParamCall(at(33), 1), plus(new Identity(at(33)), new Var(at(33), 0))), again));
    Function k = new Function(at(29), 2, 1, new If(at(34), new Identity(at(34)), uses, new Identity(at(34)), TRUTH));
    Slots main = new Slots(2);
    Node argument =
        new Comma(
            at(40),
            everyKind(plus(new Identity(at(41)), new Var(at(41), 0)), main),
            new If(at(42), new Intrinsic(at(42), LESS, List.of(new Identity(at(42)), new Const(at(42), new N(1)))), new Break(at(43), 1), new Identity(at(44)), TRUTH));
    Node call = new Call(at(45), 0, List.of(argument, new Intrinsic(at(46), TAG, List.of(new Identity(at(46))))), List.of(new Const(at(47), new N(3))));
    return new Program(new Bind(at(48), new Const(at(48), new N(1)), 0, new Label(at(49), 1, call)), List.of(k));
  }

  @Test
  void aCopyOfEveryKindRunsAsItsOriginal() {
    Program program = program();
    Frozen frozen = Compiler.compile(program, 0, CompileMode.OPTIMISED).frozen();

    assertThat(frozen.program().functions()).as("k and its copy").hasSize(2);
    assertThat(frozen.origin()).as("the origins: k's own, then k for the copy").containsExactly(0, 0);
    Function copy = frozen.program().functions().get(1);
    assertThat(copy.closures()).as("the copy's closures: h, kept").isEqualTo(1);
    assertThat(copy.values()).as("the copy's values: $m, then the free slot $n and the free label $out").isEqualTo(3);
    Call outside = (Call) ((Label) ((Bind) frozen.program().main()).body()).body();
    assertThat(outside.fn()).as("the callee of main's call").isEqualTo(1);
    assertThat(outside.values()).as("main's call passes $m, $n and $out's token").hasSize(3);
    Numbering numbering = Numbering.of(frozen.program());
    assertThat(numbering.nodes(1)).as("the copy's ParamCalls: only h's").filteredOn(ParamCall.class::isInstance).isNotEmpty().allSatisfy(n -> assertThat(((ParamCall) n).param()).as("its parameter").isZero());
    assertThat(frozen.specialised()).as("specialised(), against the nodes the copy holds").isEqualTo(numbering.nodes(1).size());

    Outcome o = run(Compiler.compile(program, 0, CompileMode.OPTIMISED), IN);
    Outcome c = run(Compiler.compile(program, 0, CompileMode.CONSERVATIVE), IN);
    assertThat(c.results()).as("premise: the original has results").isNotEmpty();
    assertThat(o.results()).as("the optimised results against CONSERVATIVE").isEqualTo(c.results());
    assertThat(o.thrown()).as("what the optimised run threw").isEqualTo(c.thrown());
    assertThat(o.answer()).as("the optimised answer").isEqualTo(c.answer());
    Outcome s = run(Compiler.compile(frozen.program(), 0, CompileMode.CONSERVATIVE), IN);
    assertThat(s.results()).as("the specialised program under CONSERVATIVE").isEqualTo(c.results());
  }

  /**
   * No call runs a copy: when the one key is refused, when the callee has no invariant parameter, and under
   * CONSERVATIVE. The program is the compiled object itself, each function its own original. Witness: under the default
   * cap the same program is rebuilt around a copy.
   */
  @Test
  void aCompileThatRunsNoCopyKeepsItsProgram() {
    Program program = new Program(new Call(at(1), 0, List.of(new Identity(at(2))), List.of()), List.of(new Function(at(0), 1, 0, countdown(0, at(0)))));
    Frozen refused = Compiler.compile(program, 0, CompileMode.OPTIMISED, 7).frozen();
    assertThat(refused.refused()).as("keys refused under a cap of 7, the key's 8 nodes").isEqualTo(1);
    assertThat(refused.program()).as("the program under a cap of 7").isSameAs(program);
    assertThat(refused.origin()).as("the origins under a cap of 7").containsExactly(0);

    Frozen conservative = Compiler.compile(program, 0, CompileMode.CONSERVATIVE).frozen();
    assertThat(conservative.program()).as("the program under CONSERVATIVE").isSameAs(program);
    assertThat(conservative.refused()).as("keys refused under CONSERVATIVE").isZero();

    Frozen built = Compiler.compile(program, 0, CompileMode.OPTIMISED).frozen();
    assertThat(built.program()).as("witness: the program under the default cap").isNotSameAs(program);
    assertThat(built.program().functions()).as("witness: the countdown and its copy").hasSize(2);

    // f is passed on as f | tag, so the countdown has no invariant parameter and no key.
    Node notInvariant =
        new If(
            at(0),
            new Identity(at(0)),
            new Comma(at(0), new ParamCall(at(0), 0), new Pipe(at(0), new Intrinsic(at(0), DEC, List.of()), new Call(at(0), 0, List.of(new Pipe(at(0), new ParamCall(at(0), 0), new Intrinsic(at(0), TAG, List.of(new Identity(at(0)))))), List.of()))),
            new Identity(at(0)),
            TRUTH);
    Program changing = new Program(new Call(at(1), 0, List.of(new Identity(at(2))), List.of()), List.of(new Function(at(0), 1, 0, notInvariant)));
    Frozen none = Compiler.compile(changing, 0, CompileMode.OPTIMISED).frozen();
    assertThat(none.program()).as("the program whose callee has no invariant parameter").isSameAs(changing);
    assertThat(none.refused()).as("keys refused where the callee has no invariant parameter").isZero();
  }

  /**
   * A class is invariant only when each member has exactly one parameter in it, passed on bare: {@code r(f; g)} calling
   * {@code r(g; f)} puts both of r's parameters in one class, and in a group whose second member passes {@code g | tag}
   * back the first member's parameter is changed. Neither builds a copy, and each runs as under CONSERVATIVE.
   */
  @Test
  void aSwappedOrChangedClassBuildsNoCopy() {
    Node again = new Pipe(at(0), new Intrinsic(at(0), DEC, List.of()), new Call(at(0), 0, List.of(new ParamCall(at(0), 1), new ParamCall(at(0), 0)), List.of()));
    Node swap = new If(at(0), new Identity(at(0)), new Comma(at(0), new ParamCall(at(0), 0), again), new Identity(at(0)), TRUTH);
    Program swapped = new Program(new Call(at(1), 0, List.of(new Const(at(2), new N(10)), new Const(at(3), new N(20))), List.of()), List.of(new Function(at(0), 2, 0, swap)));
    assertNoCopy(swapped, List.of(new N(10), new N(20), new N(0)));

    // fn0(f) calls fn1(f), fn1(g) calls fn0(g | tag): one class, fn1's g passed on bare and fn0's f changed.
    Node toSecond = new Pipe(at(0), new Intrinsic(at(0), DEC, List.of()), new Call(at(0), 1, List.of(new ParamCall(at(0), 0)), List.of()));
    Node first = new If(at(0), new Identity(at(0)), new Comma(at(0), new ParamCall(at(0), 0), toSecond), new Identity(at(0)), TRUTH);
    Node second = new Call(at(4), 0, List.of(new Pipe(at(4), new ParamCall(at(4), 0), new Intrinsic(at(4), TAG, List.of(new Identity(at(4)))))), List.of());
    Program changed = new Program(new Call(at(1), 0, List.of(new Const(at(2), new N(10))), List.of()), List.of(new Function(at(0), 1, 0, first), new Function(at(4), 1, 0, second)));
    assertNoCopy(changed, List.of(new N(10), new N(10), new N(0)));
  }

  private static void assertNoCopy(Program program, List<Object> expected) {
    Frozen frozen = Compiler.compile(program, 0, CompileMode.OPTIMISED).frozen();
    assertThat(frozen.program()).as("the program, no copy built").isSameAs(program);
    assertThat(frozen.refused()).as("keys refused").isZero();
    assertThat(run(Compiler.compile(program, 0, CompileMode.OPTIMISED), IN).results()).as("the optimised results").isEqualTo(expected);
    assertThat(run(Compiler.compile(program, 0, CompileMode.CONSERVATIVE), IN).results()).as("the results under CONSERVATIVE").isEqualTo(expected);
  }

  /**
   * A Call inside the group passes no argument of an invariant class, so the copy inlines its argument only where its
   * ParamCalls were: {@code def k(f): if . then (dec | k(f)), f else . end; k(. as $a | $a)}, the recursive call walked
   * before the ParamCall, gives a copy whose frame holds the one slot the inlined {@code $a} binds.
   */
  @Test
  void aCallInsideTheGroupInlinesNoArgument() {
    Node again = new Pipe(at(0), new Intrinsic(at(0), DEC, List.of()), new Call(at(0), 0, List.of(new ParamCall(at(0), 0)), List.of()));
    Node body = new If(at(0), new Identity(at(0)), new Comma(at(0), again, new ParamCall(at(0), 0)), new Identity(at(0)), TRUTH);
    Node argument = new Bind(at(2), new Identity(at(2)), 0, new Var(at(3), 0));
    Program program = new Program(new Call(at(1), 0, List.of(argument), List.of()), List.of(new Function(at(0), 1, 0, body)));
    Frozen frozen = Compiler.compile(program, 0, CompileMode.OPTIMISED).frozen();
    assertThat(frozen.program().functions()).as("k and its copy").hasSize(2);
    assertThat(IrVerifier.verify(frozen.program(), 0).slots()[2]).as("the copy's frame: the slot of the one inlined $a").isEqualTo(1);
    Outcome o = run(Compiler.compile(program, 0, CompileMode.OPTIMISED), IN);
    Outcome c = run(Compiler.compile(program, 0, CompileMode.CONSERVATIVE), IN);
    assertThat(c.results()).as("premise: the original has results").isNotEmpty();
    assertThat(o.results()).as("the optimised results against CONSERVATIVE").isEqualTo(c.results());
  }
}
