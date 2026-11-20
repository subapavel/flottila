package dev.rail8.jq.query.compile;

import static dev.rail8.jq.query.compile.ListPaths.AT;
import static dev.rail8.jq.query.compile.ListPaths.all;
import static dev.rail8.jq.query.compile.ListPaths.dynamic;
import static dev.rail8.jq.query.compile.ListPaths.field;
import static dev.rail8.jq.query.compile.ListPaths.getpath;
import static dev.rail8.jq.query.compile.ListPaths.index;
import static dev.rail8.jq.query.compile.ListPaths.nothing;
import static dev.rail8.jq.query.compile.ListPaths.object;
import static dev.rail8.jq.query.compile.ListPaths.raise;
import static dev.rail8.jq.query.compile.ListPaths.slice;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.Alt;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Facts;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Reduce;
import dev.rail8.jq.query.ir.Tracking;
import dev.rail8.jq.query.ir.Try;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Path variants over the toy format of {@link ListPaths}: what {@code path(f)} gives for each kind of node that passes
 * paths on, for each path rule of a step, through calls and tail jumps, and how {@code lhs |= rhs} and {@code lhs = v}
 * edit. Each program runs in both compile modes, in push form and on the trampoline (a declared stack of 0), and the
 * four runs must end alike: the same results, the same end, the same steps and the same lines in the format's log. A
 * PathOf ticks once per key of each path it gives, besides the ticks each kind has in {@code run}.
 */
class PathVariantTest {
  private static final long UNLIMITED = Long.MAX_VALUE;

  /** The toy truth: everything but null and false. */
  private static final Truth TRUTH = v -> v != null && v != Boolean.FALSE;

  private final ListPaths paths = new ListPaths();

  /** How {@code program} ran on {@code in} in {@code mode} declaring {@code stackBytes}: results, end, steps, built, log. */
  private String ran(Program program, CompileMode mode, long stackBytes, Object in) {
    paths.log.clear();
    RunState state = new RunState();
    state.begin(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, 1_000, stackBytes);
    List<Object> seen = new ArrayList<>();
    String end;
    try {
      boolean answer =
          Compiler.compile(program, 0, mode)
              .run(
                  state,
                  in,
                  v -> {
                    seen.add(v);
                    return true;
                  });
      end = "answer " + answer;
    } catch (FilterException e) {
      end = "error " + e.value();
    }
    return "results " + seen + ", " + end + ", steps " + state.steps() + ", built " + state.built() + ", log " + paths.log;
  }

  /** The end of {@code program} on {@code in}, which the four runs must agree on. */
  private String outcome(Program program, Object in) {
    String first = ran(program, CompileMode.CONSERVATIVE, UNLIMITED, in);
    for (CompileMode mode : CompileMode.values()) {
      for (long stackBytes : new long[] {UNLIMITED, 0}) {
        assertThat(ran(program, mode, stackBytes, in)).as("%s declaring %d stack bytes, against the conservative push form", mode, stackBytes).isEqualTo(first);
      }
    }
    return first;
  }

  /**
   * The end of {@code program} on {@code in} in the conservative compile, then in the optimised one: in each, push form
   * and trampoline must agree. For a program whose optimised compile specialises a function for its closure argument,
   * which drops the ParamCall and its step.
   */
  private List<String> outcomes(Program program, Object in) {
    List<String> ends = new ArrayList<>();
    for (CompileMode mode : List.of(CompileMode.CONSERVATIVE, CompileMode.OPTIMISED)) {
      String pushed = ran(program, mode, UNLIMITED, in);
      assertThat(ran(program, mode, 0, in)).as("%s on the trampoline, against push form", mode).isEqualTo(pushed);
      ends.add(pushed);
    }
    return ends;
  }

  private String outcome(Node main, Object in) {
    return outcome(new Program(main, List.of()), in);
  }

  /** {@code path(body)} on {@code in}. */
  private String pathOf(Node body, Object in) {
    return outcome(new PathOf(AT, body, paths), in);
  }

  private Node modify(Node lhs, Node rhs) {
    return new Update(AT, Update.Kind.MODIFY, lhs, rhs, paths);
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  private static Pipe pipe(Node left, Node right) {
    return new Pipe(AT, left, right);
  }

  private static Comma comma(Node left, Node right) {
    return new Comma(AT, left, right);
  }

  private static Var v(int slot) {
    return new Var(AT, slot);
  }

  /** An untracked Fn0 that gives its input plus one. */
  private static Node inc() {
    return ListPaths.call("inc", PathRule.NONE, in -> (Long) in + 1);
  }

  /** An Fn1 of {@code arg}: whether it is 0. */
  private static Node isZero(Node arg) {
    return new Intrinsic(AT, new Operation("zero", (Fn1) (state, in, a) -> (Long) a == 0), List.of(arg));
  }

  /** An Fn1 of {@code arg}: it less one. */
  private static Node dec(Node arg) {
    return new Intrinsic(AT, new Operation("dec", (Fn1) (state, in, a) -> (Long) a - 1), List.of(arg));
  }

  @Test
  void aStepAddsItsKeyAndAPipePassesThePathOn() {
    assertThat(pathOf(pipe(field("a"), index(c(1L))), object("a", List.of(5L, 6L)))).isEqualTo("results [[a, 1]], answer true, steps 2, built 0, log []");
  }

  @Test
  void aCommaGivesTheLeftPathsThenTheRight() {
    assertThat(pathOf(comma(field("a"), field("b")), object("a", 1L))).isEqualTo("results [[a], [b]], answer true, steps 3, built 0, log []");
  }

  @Test
  void aStepToEachValueAddsEachKey() {
    assertThat(pathOf(all(), List.of(5L, 6L))).isEqualTo("results [[0], [1]], answer true, steps 4, built 0, log []");
  }

  @Test
  void anIfRunsItsConditionInValueModeAndItsBranchAtThePath() {
    // In path mode the condition's step would raise on the constant; in value mode it reads 1.
    Node cond = pipe(c(object("x", 1L)), field("x"));
    assertThat(pathOf(new If(AT, cond, field("b"), field("c"), TRUTH), object())).isEqualTo("results [[b]], answer true, steps 1, built 0, log []");
  }

  @Test
  void anAltGivesTheLeftsTruthyPathsOrTheRights() {
    Node alt = new Alt(AT, field("a"), field("b"), TRUTH);
    assertThat(pathOf(alt, object("a", 1L))).isEqualTo("results [[a]], answer true, steps 1, built 0, log []");
    assertThat(pathOf(new Alt(AT, field("a"), field("b"), TRUTH), object("a", false))).isEqualTo("results [[b]], answer true, steps 1, built 0, log []");
  }

  @Test
  void aTryPassesItsBodysPathsAndItsHandlersResultsAreUntracked() {
    assertThat(pathOf(new Try(AT, field("a"), new Identity(AT)), object())).isEqualTo("results [[a]], answer true, steps 1, built 0, log []");
    assertThat(pathOf(new Try(AT, raise("x"), new Identity(AT)), object())).isEqualTo("results [], error invalid path of x, steps 0, built 0, log []");
  }

  @Test
  void aLabelPassesItsBodysPathsUntilTheBreak() {
    Node body = comma(field("a"), comma(new Break(AT, 0), field("b")));
    assertThat(pathOf(new Label(AT, 0, body), object())).isEqualTo("results [[a]], answer true, steps 2, built 0, log []");
  }

  @Test
  void aReducesStateIsUntracked() {
    // reduce .[] as $x (.; .): the state is emitted untracked.
    Node reduce = new Reduce(AT, new Identity(AT), 0, new Bind(AT, all(), Tracking.PATH, 1, new Advance(AT, 0, new Identity(AT))));
    assertThat(pathOf(reduce, List.of(5L, 6L))).isEqualTo("results [], error invalid path of [5, 6], steps 2, built 0, log []");
    // reduce (0, 1) as $i (.; .[$i]) | empty: the update's step on the untracked state raises at once.
    Node stepping = new Reduce(AT, new Identity(AT), 0, new Bind(AT, comma(c(0L), c(1L)), Tracking.PATH, 1, new Advance(AT, 0, index(v(1)))));
    assertThat(pathOf(pipe(stepping, nothing()), List.of(5L, 6L))).isEqualTo("results [], error untracked [5, 6], steps 0, built 0, log []");
  }

  @Test
  void aForeachItemKeepsItsPathAndItsStateIsUntracked() {
    // foreach .[] as $x (0; 1; $x)
    Node items = new Foreach(AT, c(0L), 0, new Bind(AT, all(), Tracking.ITEM, 1, pipe(new Advance(AT, 0, c(1L)), v(1))));
    assertThat(pathOf(items, List.of(5L, 6L))).isEqualTo("results [[0], [1]], answer true, steps 4, built 0, log []");
    // foreach .[] as $x (.; .; .)
    Node state = new Foreach(AT, new Identity(AT), 0, new Bind(AT, all(), Tracking.ITEM, 1, pipe(new Advance(AT, 0, new Identity(AT)), new Identity(AT))));
    assertThat(pathOf(state, List.of(5L, 6L))).isEqualTo("results [], error invalid path of [5, 6], steps 1, built 0, log []");
  }

  @Test
  void aForeachItemInAClosureKeepsItsPathInTheClosuresFrame() {
    // def g(c): c; path(g(foreach .[] as $x (0; 1; $x)))
    Node items = new Foreach(AT, c(0L), 0, new Bind(AT, all(), Tracking.ITEM, 1, pipe(new Advance(AT, 0, c(1L)), v(1))));
    Program program = new Program(new PathOf(AT, new Call(AT, 0, List.of(items), List.of()), paths), List.of(new Function(AT, 1, 0, new ParamCall(AT, 0))));
    // The conservative compile ticks for the Call, the ParamCall and each of the two items and keys; the optimised one
    // runs g's copy for its closure argument, with no ParamCall.
    assertThat(outcomes(program, List.of(5L, 6L)))
        .containsExactly("results [[0], [1]], answer true, steps 6, built 0, log []", "results [[0], [1]], answer true, steps 5, built 0, log []");
  }

  @Test
  void aBoundValueRunsAsItsTrackingSays() {
    // {"b": 1} | .b as $x | .a: in value mode the value reads 1; in path mode its step raises on the constant.
    Node value = pipe(c(object("b", 1L)), field("b"));
    assertThat(pathOf(new Bind(AT, value, Tracking.VALUE, 0, field("a")), object())).isEqualTo("results [[a]], answer true, steps 1, built 0, log []");
    Node tracked = pipe(c(object("b", 1L)), field("b"));
    assertThat(pathOf(new Bind(AT, tracked, Tracking.PATH, 0, field("a")), object())).isEqualTo("results [], error untracked {b=1}, steps 0, built 0, log []");
    Node binds = new Binds(AT, List.of(field("a"), pipe(c(object("b", 1L)), field("b"))), List.of(Tracking.PATH, Tracking.PATH), List.of(0, 1), field("a"));
    assertThat(pathOf(binds, object())).isEqualTo("results [], error untracked {b=1}, steps 0, built 0, log []");
    Node altBind =
        new AltBind(AT, pipe(c(object("b", 1L)), field("b")), Tracking.PATH, 0, List.of(new Alternative(new Bind(AT, v(0), 1, new Identity(AT)), List.of(1))), List.of(2), field("a"));
    assertThat(pathOf(altBind, object())).isEqualTo("results [], error untracked {b=1}, steps 0, built 0, log []");
    // .a as $x | (.a, ({"b": 1} | .b)) as $y | .c: a PATH value of many results runs the rest for each; its first
    // gives [c], then the Comma's step and the second's raise.
    Node many = new Binds(AT, List.of(field("a"), comma(field("a"), pipe(c(object("b", 1L)), field("b")))), List.of(Tracking.PATH, Tracking.PATH), List.of(0, 1), field("c"));
    assertThat(pathOf(many, object())).isEqualTo("results [[c]], error untracked {b=1}, steps 2, built 0, log []");
    // A PATH value on a tracked input runs its steps, and the body runs at the bind's own path.
    Node ok = new Binds(AT, List.of(field("a"), field("b")), List.of(Tracking.PATH, Tracking.PATH), List.of(0, 1), field("c"));
    assertThat(pathOf(ok, object("a", 1L))).isEqualTo("results [[c]], answer true, steps 1, built 0, log []");
  }

  @Test
  void anUntrackedResultRaisesOnlyWhenItComes() {
    assertThat(pathOf(pipe(field("a"), inc()), object("a", 1L))).isEqualTo("results [], error invalid path of 2, steps 0, built 0, log []");
    assertThat(pathOf(pipe(c(1L), nothing()), object())).isEqualTo("results [], answer true, steps 0, built 0, log []");
  }

  @Test
  void aSameOperationPassesItsInputsPath() {
    Node same = ListPaths.call("same", PathRule.SAME, in -> in);
    assertThat(pathOf(pipe(field("a"), same), object())).isEqualTo("results [[a]], answer true, steps 1, built 0, log []");
    FnN sameOfMany = (state, in, args) -> in;
    Node sameN = new Intrinsic(AT, new Operation("sameN", sameOfMany, new Facts(PathRule.SAME)), List.of(c(1L), c(2L), c(3L)));
    assertThat(pathOf(pipe(field("a"), sameN), object())).isEqualTo("results [[a]], answer true, steps 1, built 0, log []");
  }

  @Test
  void aStepOnAnUntrackedInputRaisesBeforeItRuns() {
    // Each step would raise "cannot index" or "cannot iterate" on 1 if it ran.
    assertThat(pathOf(pipe(c(1L), field("a")), object())).isEqualTo("results [], error untracked 1, steps 0, built 0, log []");
    assertThat(pathOf(pipe(c(1L), index(c(0L))), object())).isEqualTo("results [], error untracked 1, steps 0, built 0, log []");
    assertThat(pathOf(pipe(c(1L), dynamic(c("a"))), object())).isEqualTo("results [], error untracked 1, steps 0, built 0, log []");
    assertThat(pathOf(pipe(c(1L), slice(c(0L), c(1L))), object())).isEqualTo("results [], error untracked 1, steps 0, built 0, log []");
    assertThat(pathOf(pipe(c(1L), all()), object())).isEqualTo("results [], error untracked 1, steps 0, built 0, log []");
  }

  @Test
  void aDynamicStepAddsItsArgument() {
    assertThat(pathOf(dynamic(c("a")), object())).isEqualTo("results [[a]], answer true, steps 1, built 0, log []");
  }

  @Test
  void aSliceChargesTheKeyItBuildsInAPathVariantOnly() {
    assertThat(pathOf(slice(c(0L), c(1L)), List.of(5L, 6L))).isEqualTo("results [[[0, 1]]], answer true, steps 1, built " + ListPaths.SLICE_KEY + ", log []");
    assertThat(outcome(slice(c(0L), c(1L)), List.of(5L, 6L))).isEqualTo("results [[5]], answer true, steps 0, built 0, log []");
  }

  @Test
  void aKeyListStepAddsEachKeyAndIsUntrackedOnAnUntrackedInput() {
    assertThat(pathOf(getpath(c(List.of("a", "b"))), object())).isEqualTo("results [[a, b]], answer true, steps 2, built 0, log []");
    assertThat(pathOf(pipe(c(null), getpath(c(List.of("a")))), object())).isEqualTo("results [], error invalid path of null, steps 0, built 0, log []");
  }

  @Test
  void aCallAndAClosurePassThePathOn() {
    // def f: .a; path(f | .b)
    Program call = new Program(new PathOf(AT, pipe(new Call(AT, 0, List.of(), List.of()), field("b")), paths), List.of(new Function(AT, 0, 0, field("a"))));
    assertThat(outcome(call, object())).isEqualTo("results [[a, b]], answer true, steps 3, built 0, log []");
    // def g(c): c | .b; path(g(.a))
    Program closure = new Program(new PathOf(AT, new Call(AT, 0, List.of(field("a")), List.of()), paths), List.of(new Function(AT, 1, 0, pipe(new ParamCall(AT, 0), field("b")))));
    // The Call, the ParamCall and two keys; the optimised compile runs g's copy for .a, with no ParamCall.
    assertThat(outcomes(closure, object())).containsExactly("results [[a, b]], answer true, steps 4, built 0, log []", "results [[a, b]], answer true, steps 3, built 0, log []");
  }

  @Test
  void aTailJumpCarriesThePath() {
    // def f($n): if $n == 0 then .a else ($n - 1) as $m | .b | f($m) end; path(f(3))
    Node self = new If(AT, isZero(v(0)), field("a"), new Bind(AT, dec(v(0)), 1, pipe(field("b"), new Call(AT, 0, List.of(), List.of(v(1))))), TRUTH);
    Program selfJump = new Program(new PathOf(AT, new Call(AT, 0, List.of(), List.of(c(3L))), paths), List.of(new Function(AT, 0, 1, self)));
    assertThat(outcome(selfJump, object())).isEqualTo("results [[b, b, b, a]], answer true, steps 8, built 0, log []");
    // def f($n): if $n == 0 then .a else ($n - 1) as $m | g($m) end; def g($n): .b | f($n); path(f(2))
    Node f = new If(AT, isZero(v(0)), field("a"), new Bind(AT, dec(v(0)), 1, new Call(AT, 1, List.of(), List.of(v(1)))), TRUTH);
    Node g = pipe(field("b"), new Call(AT, 0, List.of(), List.of(v(0))));
    Program groupJump = new Program(new PathOf(AT, new Call(AT, 0, List.of(), List.of(c(2L))), paths), List.of(new Function(AT, 0, 1, f), new Function(AT, 0, 1, g)));
    assertThat(outcome(groupJump, object())).isEqualTo("results [[b, b, a]], answer true, steps 8, built 0, log []");
  }

  @Test
  void aModifyGetsRunsTheRhsAndSetsEachPath() {
    assertThat(outcome(modify(field("a"), inc()), object("a", 1L))).isEqualTo("results [{a=2}], answer true, steps 0, built 0, log [get [a], set [a] 2, end]");
  }

  @Test
  void theLhsAndTheRhsRunInTurn() {
    // [1, 2] | (.[] | debug("L")) |= (debug("R") | . + 1)
    Node update = modify(pipe(all(), paths.debug("L")), pipe(paths.debug("R"), inc()));
    assertThat(outcome(update, List.of(1L, 2L))).isEqualTo("results [[2, 3]], answer true, steps 2, built 0, log [L 1, get [0], R 1, set [0] 2, L 2, get [1], R 2, set [1] 3, end]");
  }

  @Test
  void anUntrackedLhsResultRaisesOnlyWhenItComes() {
    // (1 | empty) |= 1 gives its input.
    assertThat(outcome(modify(pipe(c(1L), nothing()), c(1L)), object("a", 1L))).isEqualTo("results [{a=1}], answer true, steps 0, built 0, log [end]");
    assertThat(outcome(modify(c(1L), c(2L)), object())).isEqualTo("results [], error invalid path of 1, steps 0, built 0, log []");
  }

  @Test
  void anEmptyRhsDeletesThePathAtTheEnd() {
    // {"a": 0, "b": 1} | .a |= empty
    assertThat(outcome(modify(field("a"), nothing()), object("a", 0L, "b", 1L))).isEqualTo("results [{b=1}], answer true, steps 0, built 0, log [get [a], delete [a], end]");
  }

  @Test
  void theFirstRhsResultWins() {
    assertThat(outcome(modify(field("a"), comma(c(1L), c(2L))), object("a", 0L))).isEqualTo("results [{a=1}], answer true, steps 0, built 0, log [get [a], set [a] 1, end]");
  }

  @Test
  void anAssignSetsEachPathToItsValue() {
    // 9 as $v | (.a, .b) = $v
    Node assign = new Bind(AT, c(9L), 0, new Update(AT, Update.Kind.ASSIGN, comma(field("a"), field("b")), v(0), paths));
    assertThat(outcome(assign, object("a", 0L))).isEqualTo("results [{a=9, b=9}], answer true, steps 1, built 0, log [set [a] 9, set [b] 9, end]");
    // .a = . reads the input once, before the lhs runs.
    Node input = new Update(AT, Update.Kind.ASSIGN, field("a"), new Identity(AT), paths);
    assertThat(outcome(input, object("a", 0L))).isEqualTo("results [{a={a=0}}], answer true, steps 0, built 0, log [set [a] {a=0}, end]");
  }

  @Test
  void aBreakAbandonsTheEdit() {
    // label $l | .a |= break $l
    assertThat(outcome(new Label(AT, 0, modify(field("a"), new Break(AT, 0))), object())).isEqualTo("results [], answer true, steps 0, built 0, log [get [a]]");
    // label $l | (.a, break $l, .b) |= 1
    Node lhs = comma(field("a"), comma(new Break(AT, 0), field("b")));
    assertThat(outcome(new Label(AT, 0, modify(lhs, c(1L))), object())).isEqualTo("results [], answer true, steps 1, built 0, log [get [a], set [a] 1]");
  }

  @Test
  void anErrorAbandonsTheEdit() {
    assertThat(outcome(modify(comma(field("a"), field("b")), raise("x")), object())).isEqualTo("results [], error x, steps 0, built 0, log [get [a]]");
  }

  @Test
  void anRhsErrorPassesTheLhssTryWrapped() {
    // (try (.a, .b) catch "caught") |= error("x")
    Node lhs = new Try(AT, comma(field("a"), field("b")), c("caught"));
    assertThat(outcome(modify(lhs, raise("x")), object())).isEqualTo("results [], error x, steps 0, built 0, log [get [a]]");
  }

  @Test
  void anRhsErrorStartsTheLhssNextAlternativeWithTheEditInUse() {
    // {"a":[1],"b":3,"c":5,"d":0} | (.a as $x ?// $y | if $x then (.b, .d) else .c end) |= (if . == 0 then error("e") else 7 end)
    Node lhs =
        new AltBind(
            AT,
            field("a"),
            0,
            List.of(new Alternative(new Bind(AT, v(0), 1, new Identity(AT)), List.of(1, Alternative.NULL)), new Alternative(new Bind(AT, v(0), 2, new Identity(AT)), List.of(Alternative.NULL, 2))),
            List.of(3, 4),
            new If(AT, v(3), comma(field("b"), field("d")), field("c"), TRUTH));
    Node rhs = new If(AT, isZero(new Identity(AT)), raise("e"), c(7L), TRUTH);
    Object in = object("a", List.of(1L), "b", 3L, "c", 5L, "d", 0L);
    assertThat(outcome(modify(lhs, rhs), in)).isEqualTo("results [{a=[1], b=7, c=7, d=0}], answer true, steps 2, built 0, log [get [b], set [b] 7, get [d], get [c], set [c] 7, end]");
  }

  @Test
  void anUpdateInsideAPathOfIsUntracked() {
    assertThat(pathOf(modify(field("a"), c(1L)), object())).isEqualTo("results [], error invalid path of {a=1}, steps 0, built 0, log [get [a], set [a] 1, end]");
  }
}
