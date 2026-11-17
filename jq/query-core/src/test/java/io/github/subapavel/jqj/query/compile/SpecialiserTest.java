package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.Specialising.EMPTY;
import static io.github.subapavel.jqj.query.compile.Specialising.LESS;
import static io.github.subapavel.jqj.query.compile.Specialising.LIST;
import static io.github.subapavel.jqj.query.compile.Specialising.PLUS;
import static io.github.subapavel.jqj.query.compile.Specialising.TRUTH;
import static io.github.subapavel.jqj.query.compile.Specialising.at;
import static io.github.subapavel.jqj.query.compile.Specialising.countdown;
import static io.github.subapavel.jqj.query.compile.Specialising.nodes;
import static io.github.subapavel.jqj.query.compile.Specialising.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.Specialising.N;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
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
import io.github.subapavel.jqj.query.ir.Var;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The specialisation pass at the compile seam (#117, ADR-0045 "Amendment (ticket #117)", ADR-0006, GLOSSARY
 * "Specialisation", "Outside call", "Invariant parameter"): {@link Compiler#compile} with a test-only cap, then {@code
 * Executable.frozen()}, the specialised program, the origin of each copy and the specialisation counts (the seams are
 * named in {@link Specialising}). Copies are appended after the originals in the order their keys are built; a key is
 * built when {@code spent + nodes <= cap}, else refused at all its calls, which keep their real closures. Every
 * program here is checked against the conservative compile of the same program, which makes no copy (ADR-0018): the
 * same results, errors and answer.
 *
 * <p>Node counts are the copies' after the argument replaces each ParamCall and a bare ParamCall argument leaves an
 * internal Call: {@link Specialising#countdown} holds 9 nodes, so its copy for an argument of k nodes holds 7 + k; a
 * function whose body is {@code ParamCall 0} copies to its argument's k.
 */
class SpecialiserTest {
  /** The input of every run: the countdowns below go 2, 1, 0. */
  private static final Object IN = new N(2);

  private static Executable compile(Program program, CompileMode mode, int cap) {
    return Compiler.compile(program, 0, mode, cap);
  }

  /** The program's optimised compile under {@code cap} ends as its conservative compile does, steps apart. */
  private static void assertSameOutcome(Program optimised, Program conservative, int cap, String what) {
    Outcome o = run(compile(optimised, CompileMode.OPTIMISED, cap), IN);
    Outcome c = run(Compiler.compile(conservative, 0, CompileMode.CONSERVATIVE), IN);
    assertThat(c.thrown()).as("%s: CONSERVATIVE ends without an error or a termination", what).isNull();
    assertThat(o.results()).as("%s: results under cap %d against CONSERVATIVE", what, cap).isEqualTo(c.results());
    assertThat(o.thrown()).as("%s: what the run under cap %d threw", what, cap).isEqualTo(c.thrown());
    assertThat(o.answer()).as("%s: the answer under cap %d", what, cap).isEqualTo(c.answer());
  }

  /** {@code f(g): g}, closure parameter 0 run once: a PLAIN function. */
  private static Function runsItsClosure(int at) {
    return new Function(at(at), 1, 0, new ParamCall(at(at), 0));
  }

  private static Call call(int at, int fn, Node closure) {
    return new Call(at(at), fn, List.of(closure), List.of());
  }

  /**
   * f0 and f1 each run their closure; main calls f0 at byte 20 first in its pre-order, then f1 at byte 10, each with an
   * argument of 5 nodes, so each key costs 5.
   */
  private static Program twoKeys() {
    Node main = new Comma(at(30), call(20, 0, nodes(5, at(21))), call(10, 1, nodes(5, at(11))));
    return new Program(main, List.of(runsItsClosure(0), runsItsClosure(2)));
  }

  /**
   * A cap of 8 fits one of the two keys of 5. Keys go by Source before the Program's pre-order, so f1's call at byte 10
   * runs a copy and f0's at byte 20, first in main's pre-order, is refused and keeps its real closure. Two equal
   * programs of fresh nodes give equal programs, origins and counts.
   */
  @Test
  void refusalIsDeterministic() {
    Frozen first = compile(twoKeys(), CompileMode.OPTIMISED, 8).frozen();
    Frozen second = compile(twoKeys(), CompileMode.OPTIMISED, 8).frozen();

    assertThat(second.program()).as("the specialised program of an equal program of fresh nodes").isEqualTo(first.program());
    assertThat(second.origin()).as("the origins of an equal program of fresh nodes").containsExactly(first.origin());
    assertThat(second.refused()).as("refused of an equal program of fresh nodes").isEqualTo(first.refused());

    assertThat(first.refused()).as("keys refused under a cap of 8").isEqualTo(1);
    assertThat(first.specialised()).as("nodes spent under a cap of 8").isEqualTo(5);
    assertThat(first.program().functions()).as("f0, f1 and one copy").hasSize(3);
    Comma main = (Comma) first.program().main();
    Call later = (Call) main.left();
    Call earlier = (Call) main.right();
    assertThat(later.fn()).as("the callee of f0's call at byte 20, the later Source").isZero();
    assertThat(later.closures()).as("the closures of f0's call at byte 20").hasSize(1);
    assertThat(earlier.closures()).as("the closures of f1's call at byte 10").isEmpty();
    assertThat(earlier.fn()).as("the callee of f1's call at byte 10").isEqualTo(2);
    assertThat(first.origin()[2]).as("the original of the copy at place 2").isEqualTo(1);
    assertSameOutcome(twoKeys(), twoKeys(), 8, "two keys of 5");
  }

  /**
   * a(f): f, called at byte 10 with 10 nodes; the group b0(f): b1(f) and b1, a countdown calling b0, called at byte 20
   * with 12 nodes, which cost 1 + (7 + 12) = 20 together and 1 for b0 alone; c(f): f, called at byte 30 with 5 nodes.
   */
  private static Program threeKeys() {
    Function a = runsItsClosure(0);
    Function b0 = new Function(at(2), 1, 0, call(2, 2, new ParamCall(at(2), 0)));
    Function b1 = new Function(at(4), 1, 0, countdown(1, at(4)));
    Function c = runsItsClosure(6);
    Node main = new Comma(at(40), call(10, 0, nodes(10, at(11))), new Comma(at(41), call(20, 1, nodes(12, at(21))), call(30, 3, nodes(5, at(31)))));
    return new Program(main, List.of(a, b0, b1, c));
  }

  /**
   * The design's case, costs 10, 20 and 5 with cap 16: built, refused, built. The group's key is refused whole, though
   * b0's copy alone (1 node) would fit what is left (6); the key after it, smaller, still fits. Then the edges of
   * {@code spent + nodes <= cap}: at 15 the last key fits exactly, at 14 it does not, and at 30 the first two fit
   * exactly and the last is refused.
   */
  @ParameterizedTest(name = "cap {0}")
  @CsvSource({"16, true, false, true, 15", "15, true, false, true, 15", "14, true, false, false, 10", "30, true, true, false, 30"})
  void capSpendsWholeKeysInSourceOrder(int cap, boolean first, boolean second, boolean third, int spent) {
    Frozen frozen = compile(threeKeys(), CompileMode.OPTIMISED, cap).frozen();
    List<Call> calls = new ArrayList<>();
    Comma main = (Comma) frozen.program().main();
    calls.add((Call) main.left());
    calls.add((Call) ((Comma) main.right()).left());
    calls.add((Call) ((Comma) main.right()).right());
    boolean[] built = {first, second, third};
    int[] originals = {0, 1, 3};
    int copies = 0;
    for (int k = 0; k < 3; k++) {
      Call c = calls.get(k);
      if (built[k]) {
        assertThat(c.fn()).as("cap %d: the callee of key %d, a copy appended after the four originals", cap, k + 1).isGreaterThanOrEqualTo(4);
        assertThat(frozen.origin()[c.fn()]).as("cap %d: the original of key %d's copy", cap, k + 1).isEqualTo(originals[k]);
        assertThat(c.closures()).as("cap %d: the closures of key %d's call", cap, k + 1).isEmpty();
        copies += k == 1 ? 2 : 1;
      } else {
        assertThat(c.fn()).as("cap %d: the callee of the refused key %d", cap, k + 1).isEqualTo(originals[k]);
        assertThat(c.closures()).as("cap %d: the closures of the refused key %d's call", cap, k + 1).hasSize(1);
      }
    }
    assertThat(frozen.program().functions()).as("cap %d: four originals and the copies of the built keys", cap).hasSize(4 + copies);
    assertThat(frozen.specialised()).as("cap %d: the nodes spent", cap).isEqualTo(spent);
    assertThat(frozen.refused()).as("cap %d: the keys refused", cap).isEqualTo((first ? 0 : 1) + (second ? 0 : 1) + (third ? 0 : 1));
    assertThat(frozen.program().functions().subList(0, 4)).as("cap %d: the originals, unchanged", cap).isEqualTo(threeKeys().functions());
    assertSameOutcome(threeKeys(), threeKeys(), cap, "three keys under cap " + cap);
  }

  /**
   * g, a countdown, is called at byte 10 with {@code .}, a key of 8 nodes; h(f): f | g(.) at byte 20 with {@code .}, a
   * key of 4. Under a cap of 7 g's key is refused and h's built, and h's copy calls g with the same tuple, so that call,
   * met later, keeps its real closure too.
   */
  private static Program refusedTwice() {
    Function g = new Function(at(0), 1, 0, countdown(0, at(0)));
    Function h = new Function(at(4), 1, 0, new Pipe(at(4), new ParamCall(at(4), 0), call(40, 0, new Identity(at(41)))));
    Node main = new Comma(at(50), call(10, 0, new Identity(at(11))), call(20, 1, new Identity(at(21))));
    return new Program(main, List.of(g, h));
  }

  @Test
  void refusedKeyKeepsRealClosuresAtEveryCall() {
    Frozen frozen = compile(refusedTwice(), CompileMode.OPTIMISED, 7).frozen();

    assertThat(frozen.refused()).as("keys refused under a cap of 7").isEqualTo(1);
    assertThat(frozen.specialised()).as("nodes spent under a cap of 7: h's copy").isEqualTo(4);
    assertThat(frozen.program().functions()).as("g, h and h's copy").hasSize(3);
    assertThat(frozen.origin()[2]).as("the original of the copy at place 2").isEqualTo(1);
    Comma main = (Comma) frozen.program().main();
    Call g = (Call) main.left();
    Call h = (Call) main.right();
    assertThat(g.fn()).as("the callee of main's call of g").isZero();
    assertThat(g.closures()).as("the closures of main's call of g").hasSize(1);
    assertThat(h.fn()).as("the callee of main's call of h").isEqualTo(2);
    Call inCopy = (Call) ((Pipe) frozen.program().functions().get(2).body()).right();
    assertThat(inCopy.fn()).as("the callee of the call of g in h's copy").isZero();
    assertThat(inCopy.closures()).as("the closures of the call of g in h's copy").hasSize(1);
    assertSameOutcome(refusedTwice(), refusedTwice(), 7, "g refused under cap 7");

    // Witness: under the default cap both calls of g run one copy, with no closure.
    Frozen all = Compiler.compile(refusedTwice(), 0, CompileMode.OPTIMISED).frozen();
    Comma allMain = (Comma) all.program().main();
    Call allG = (Call) allMain.left();
    Call allInCopy = (Call) ((Pipe) all.program().functions().get(((Call) allMain.right()).fn()).body()).right();
    assertThat(all.refused()).as("keys refused under the default cap").isZero();
    assertThat(all.origin()[allG.fn()]).as("the original of the callee of main's call of g under the default cap").isZero();
    assertThat(allG.closures()).as("the closures of main's call of g under the default cap").isEmpty();
    assertThat(allInCopy.fn()).as("the callee of the call of g in h's copy under the default cap").isEqualTo(allG.fn());
  }

  /**
   * Three calls of the countdown g, inside {@code 5 as $1 | 7 as $2}: {@code . as $3 | plus($3; $1)}, {@code . as $4 |
   * plus($4; $2)} and {@code . as $5 | plus'($5; $1)}, plus' an Operation equal to plus but another instance. The first
   * two have one shape: their own slots renamed, their free slots numbered by first occurrence, the same Operation. So
   * they share a copy that takes the free slot as a value, each call passing its own; the third gets a copy of its own.
   */
  private static Program shapes(Operation third) {
    Function g = new Function(at(0), 1, 0, countdown(0, at(0)));
    Node a = new Bind(at(11), new Identity(at(11)), 3, new Intrinsic(at(11), PLUS, List.of(new Var(at(11), 3), new Var(at(11), 1))));
    Node b = new Bind(at(21), new Identity(at(21)), 4, new Intrinsic(at(21), PLUS, List.of(new Var(at(21), 4), new Var(at(21), 2))));
    Node c = new Bind(at(31), new Identity(at(31)), 5, new Intrinsic(at(31), third, List.of(new Var(at(31), 5), new Var(at(31), 1))));
    Node calls = new Comma(at(40), new Comma(at(41), call(10, 0, a), call(20, 0, b)), call(30, 0, c));
    Node main = new Bind(at(1), new Const(at(1), new N(5)), 1, new Bind(at(2), new Const(at(2), new N(7)), 2, calls));
    return new Program(main, List.of(g));
  }

  @Test
  void sameShapeSharesACopy() {
    Operation again = new Operation(PLUS.name(), PLUS.form());
    assertThat(again).as("premise: the third call's Operation equals plus as a record").isEqualTo(PLUS).isNotSameAs(PLUS);

    Frozen frozen = Compiler.compile(shapes(again), 0, CompileMode.OPTIMISED).frozen();
    Comma calls = (Comma) ((Bind) ((Bind) frozen.program().main()).body()).body();
    Call a = (Call) ((Comma) calls.left()).left();
    Call b = (Call) ((Comma) calls.left()).right();
    Call c = (Call) calls.right();
    assertThat(frozen.program().functions()).as("g and two copies").hasSize(3);
    assertThat(a.fn()).as("the callee of the first call").isNotZero();
    assertThat(b.fn()).as("the callee of the second call, of the first's shape").isEqualTo(a.fn());
    assertThat(c.fn()).as("the callee of the third call, another Operation instance").isNotZero().isNotEqualTo(a.fn());
    assertThat(frozen.origin()[a.fn()]).as("the original of the shared copy").isZero();
    assertThat(frozen.origin()[c.fn()]).as("the original of the third call's copy").isZero();
    Function shared = frozen.program().functions().get(a.fn());
    assertThat(shared.closures()).as("the closures of the shared copy").isZero();
    assertThat(shared.values()).as("the values of the shared copy: the free slot").isEqualTo(1);
    assertThat(a.values()).as("the values the first call passes").hasSize(1).first().isInstanceOfSatisfying(Var.class, v -> assertThat(v.slot()).as("its slot").isEqualTo(1));
    assertThat(b.values()).as("the values the second call passes").hasSize(1).first().isInstanceOfSatisfying(Var.class, v -> assertThat(v.slot()).as("its slot").isEqualTo(2));
    assertSameOutcome(shapes(again), shapes(new Operation(PLUS.name(), PLUS.form())), Specialiser.SPECIALISE_CAP, "three shapes");
  }

  /**
   * The filter {@code def r(f): if . < 2 then ., (f | r(f)) else empty end; [0|r(.+1)],[0|r(.+1)]}, each literal 1
   * its own constant ({@code [limit(2;0|recurse(.+1))]} bounded by r itself). The reference binary (1.8.2) gives {@code [0,1]}
   * twice (.logs/117-jqref.log; specialise/literal-arguments is the unbounded filter's recording).
   */
  private static Program literals(N one, N other) {
    Node body =
        new If(
            at(0),
            new Intrinsic(at(0), LESS, List.of(new Identity(at(0)), new Const(at(0), new N(2)))),
            new Comma(at(0), new Identity(at(0)), new Pipe(at(0), new ParamCall(at(0), 0), call(0, 0, new ParamCall(at(0), 0)))),
            new Intrinsic(at(0), EMPTY, List.of()),
            TRUTH);
    Node first = new Collect(at(10), new Pipe(at(10), new Const(at(10), new N(0)), call(12, 0, plusOne(13, one))), LIST);
    Node second = new Collect(at(20), new Pipe(at(20), new Const(at(20), new N(0)), call(22, 0, plusOne(23, other))), LIST);
    return new Program(new Comma(at(30), first, second), List.of(new Function(at(0), 1, 0, body)));
  }

  private static Node plusOne(int at, N one) {
    return new Intrinsic(at(at), PLUS, List.of(new Identity(at(at)), new Const(at(at), one)));
  }

  private static List<Call> callsOfR(Frozen frozen) {
    Comma main = (Comma) frozen.program().main();
    return List.of(
        (Call) ((Pipe) ((Collect) main.left()).body()).right(), (Call) ((Pipe) ((Collect) main.right()).body()).right());
  }

  @Test
  void literalArgumentsGetACopyEach() {
    N one = new N(1);
    N other = new N(1);
    assertThat(other).as("premise: the two literals are equal values of two identities").isEqualTo(one).isNotSameAs(one);

    Frozen frozen = Compiler.compile(literals(one, other), 0, CompileMode.OPTIMISED).frozen();
    List<Call> calls = callsOfR(frozen);
    assertThat(frozen.program().functions()).as("r and a copy per literal").hasSize(3);
    assertThat(calls).extracting(Call::fn).as("the callees of the two calls").doesNotHaveDuplicates().doesNotContain(0);
    assertThat(calls).extracting(c -> frozen.origin()[c.fn()]).as("the originals of the two copies").containsExactly(0, 0);
    List<Object> pair = List.of(new N(0), new N(1));
    for (CompileMode mode : CompileMode.values()) {
      Outcome o = run(Compiler.compile(literals(new N(1), new N(1)), 0, mode), IN);
      assertThat(o.results()).as("%s: the results, the reference binary's [0,1] twice", mode).containsExactly(pair, pair);
    }

    // Witness: one constant object in both calls is one shape, and one copy.
    N same = new N(1);
    Frozen shared = Compiler.compile(literals(same, same), 0, CompileMode.OPTIMISED).frozen();
    assertThat(shared.program().functions()).as("r and one copy, for one constant object").hasSize(2);
    assertThat(callsOfR(shared)).extracting(Call::fn).as("the callees of the two calls of one constant").containsExactly(1, 1);
  }

  /** f(g) of body {@code body}, one closure parameter and no value: PLAIN, fn0. */
  private static Function ofOneClosure(Node body) {
    return new Function(at(0), 1, 0, body);
  }

  /** {@code label $l | f(break $l), 7}, the Label's slot 0, f's body {@code body}. */
  private static Program breakingClosure(Node body) {
    Node call = new Call(at(12), 0, List.of(new Break(at(14), 0)), List.of());
    return new Program(new Label(at(10), 0, new Comma(at(11), call, new Const(at(16), new N(7)))), List.of(ofOneClosure(body)));
  }

  /** {@code f(argument)}, f's body {@code g}: its closure run once. */
  private static Program runsTheClosure(Node argument) {
    return new Program(new Call(at(10), 0, List.of(argument), List.of()), List.of(ofOneClosure(new ParamCall(at(1), 0))));
  }

  /**
   * #116's four push Calls, each as ValueFormTest's shapes 9, 10, 26 and 27 were before #117 (65088d3), on the numbers
   * here: its program, its results there, and the runs of the ParamCall its argument replaces.
   */
  static Stream<Arguments> pushCalls() {
    return Stream.of(
        Arguments.argumentSet("shape 9: label $l | f(break $l), 7, f's body 5", (Supplier<Program>) () -> breakingClosure(new Const(at(1), new N(5))), List.of(new N(5), new N(7)), 0L),
        Arguments.argumentSet("shape 10: label $l | f(break $l), 7, f's body of no result", (Supplier<Program>) () -> breakingClosure(new Intrinsic(at(1), EMPTY, List.of())), List.of(new N(7)), 0L),
        Arguments.argumentSet("shape 26: f(5), f's body its closure", (Supplier<Program>) () -> runsTheClosure(new Const(at(11), new N(5))), List.of(new N(5)), 1L),
        Arguments.argumentSet("shape 27: f(empty), f's body its closure", (Supplier<Program>) () -> runsTheClosure(new Intrinsic(at(11), EMPTY, List.of())), List.of(), 1L));
  }

  /**
   * A copy makes #116's push Calls value form (#117 re-pin ruling V4; ADR-0045 "Amendment (ticket #117)", ADR-0047
   * "Amendment (ticket #116)"). In each program f's closure parameter is invariant, since no Call inside f's group passes
   * it anything, so the optimised compile copies f for main's argument and main's Call runs the copy with no closure.
   * The copy holds no ParamCall and no Break, so its root and main's Call are value form. The run gives the conservative
   * compile's results, answer and error, and steps by SpecialiserPropertyTest's rule: the conservative run's less one
   * for each run of the ParamCall the argument replaced, none where f's body never runs g. Witness: under a cap of 0 the
   * key is refused and main's Call keeps its real closure and is push form, as #116 pinned it.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("pushCalls")
  void aCopyMakesAPushCallValueForm(Supplier<Program> program, List<Object> results, long replaced) {
    Executable optimised = Compiler.compile(program.get(), 0, CompileMode.OPTIMISED);
    Frozen frozen = optimised.frozen();
    List<Node> main = frozen.numbering().nodes(-1);
    List<Integer> calls = new ArrayList<>();
    for (int id = 0; id < main.size(); id++) {
      if (main.get(id) instanceof Call) {
        calls.add(id);
      }
    }
    assertThat(calls).as("the freeze ids of main's Calls").hasSize(1);
    int id = calls.getFirst();
    Call call = (Call) main.get(id);
    assertThat(frozen.program().functions()).as("f and its copy").hasSize(2);
    assertThat(call.fn()).as("the callee of main's Call: the copy at place 1").isEqualTo(1);
    assertThat(frozen.origin()[1]).as("the original of the copy at place 1").isZero();
    assertThat(call.closures()).as("the closures of main's Call").isEmpty();
    assertThat(optimised.form(-1, id)).as("the run form of main's Call, node %d of main", id).isEqualTo(RunForm.VALUE);
    assertThat(optimised.form(1, 0)).as("the run form of the copy's root, node 0 of place 1").isEqualTo(RunForm.VALUE);

    Outcome c = run(Compiler.compile(program.get(), 0, CompileMode.CONSERVATIVE), IN);
    Outcome o = run(optimised, IN);
    assertThat(c.thrown()).as("CONSERVATIVE ends without an error or a termination").isNull();
    assertThat(c.results()).as("results under CONSERVATIVE").isEqualTo(results);
    assertThat(o.results()).as("results under OPTIMISED against CONSERVATIVE").isEqualTo(c.results());
    assertThat(o.thrown()).as("what the run under OPTIMISED threw").isEqualTo(c.thrown());
    assertThat(o.answer()).as("the answer under OPTIMISED").isEqualTo(c.answer());
    assertThat(c.steps() - o.steps()).as("CONSERVATIVE's %d steps less OPTIMISED's %d, against %d runs of the replaced ParamCall", c.steps(), o.steps(), replaced).isEqualTo(replaced);

    Executable refused = compile(program.get(), CompileMode.OPTIMISED, 0);
    assertThat(refused.frozen().refused()).as("witness: keys refused under a cap of 0").isEqualTo(1);
    assertThat(refused.form(-1, id)).as("witness: the run form of main's Call under a cap of 0, its real closure kept").isEqualTo(RunForm.PUSH);
  }
}
