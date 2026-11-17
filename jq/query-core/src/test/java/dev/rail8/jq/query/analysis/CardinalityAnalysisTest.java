package dev.rail8.jq.query.analysis;

import static dev.rail8.jq.query.analysis.Cardinality.AT_MOST_ONE;
import static dev.rail8.jq.query.analysis.Cardinality.MANY;
import static dev.rail8.jq.query.analysis.Cardinality.NONE;
import static dev.rail8.jq.query.analysis.Cardinality.ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.Alt;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Collect;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Reduce;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Try;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.RunState;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The cardinality analysis, the freeze and the call groups at query-core's own seam (ADR-0007 and ADR-0045, their #112
 * amendments; F-56), over hand-built programs: one case per rule, the fixpoint over recursive groups, the conservative
 * facts, the freeze ids and the groups' order. The analysis reads node kinds and call forms only, so the operations
 * here never run.
 */
class CardinalityAnalysisTest {
  private static final Source AT = new Source(0, 1);

  /** An operation of a one-result call form. */
  private static Intrinsic fn0() {
    return new Intrinsic(AT, new Operation("one", (Fn0) (state, in) -> in), List.of());
  }

  /** An operation of the one-result call form {@code form}, with {@code args}. */
  private static Intrinsic intrinsic(CallForm form, Node... args) {
    return new Intrinsic(AT, new Operation("form", form), List.of(args));
  }

  /** An operation of the Many call form. */
  private static Intrinsic many() {
    return new Intrinsic(
        AT,
        new Operation(
            "many",
            new Many() {
              @Override
              public long first(RunState state, Object in, Object[] args) {
                return END;
              }

              @Override
              public long next(RunState state, Object in, Object[] args, long cursor) {
                return END;
              }

              @Override
              public Object value(RunState state, Object in, Object[] args, long cursor) {
                return in;
              }
            }),
        List.of());
  }

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

  private static Label label(int slot, Node body) {
    return new Label(AT, slot, body);
  }

  private static Break brk(int slot) {
    return new Break(AT, slot);
  }

  private static Call call(int fn, Node... closures) {
    return new Call(AT, fn, List.of(closures), List.of());
  }

  private static Function function(int closures, Node body) {
    return new Function(AT, closures, 0, body);
  }

  /** A program analysed in the optimised compile: its numbering and its facts. */
  private record Analysed(Numbering numbering, Facts facts) {
    static Analysed of(Node main, Function... functions) {
      Program program = new Program(main, List.of(functions));
      Numbering numbering = Numbering.of(program);
      return new Analysed(numbering, Facts.optimised(numbering, CallGraph.of(program, numbering)));
    }

    /** The fact of {@code node} of the body {@code body}, -1 for the main tree. */
    Cardinality of(int body, Node node) {
      return facts.cardinality(body, numbering.id(node));
    }
  }

  /**
   * Each case's main tree, built fresh per run, and the fact of its root. A NEVER part is told from a NONE one by a
   * Comma after it inside a Label: {@code label(comma(n, c))} is NONE when n is NEVER and at least AT_MOST_ONE when n
   * may end normally.
   */
  static Stream<Arguments> rules() {
    return Stream.of(
        Arguments.argumentSet("Identity is ONE", (Supplier<Node>) () -> id(), ONE),
        Arguments.argumentSet("Const is ONE", (Supplier<Node>) () -> c(), ONE),
        Arguments.argumentSet("Var is ONE, and so the Bind of a Const", (Supplier<Node>) () -> new Bind(AT, c(), 0, new Var(AT, 0)), ONE),
        Arguments.argumentSet("an Intrinsic of the Fn0 form is AT_MOST_ONE", (Supplier<Node>) () -> fn0(), AT_MOST_ONE),
        Arguments.argumentSet("an Intrinsic of the Fn1 form is AT_MOST_ONE", (Supplier<Node>) () -> intrinsic((Fn1) (state, in, a) -> a, c()), AT_MOST_ONE),
        Arguments.argumentSet("an Intrinsic of the Fn2 form is AT_MOST_ONE", (Supplier<Node>) () -> intrinsic((Fn2) (state, in, a, b) -> a, c(), c()), AT_MOST_ONE),
        Arguments.argumentSet("an Intrinsic of the FnN form is AT_MOST_ONE", (Supplier<Node>) () -> intrinsic((FnN) (state, in, args) -> in, c(), c(), c()), AT_MOST_ONE),
        Arguments.argumentSet("an Intrinsic of the Many form is MANY", (Supplier<Node>) () -> many(), MANY),
        Arguments.argumentSet("a Break is NEVER", (Supplier<Node>) () -> label(0, comma(brk(0), c())), NONE),
        Arguments.argumentSet(
            "an AltBind is MANY", (Supplier<Node>) () -> new AltBind(AT, c(), 0, List.of(new Alternative(id(), List.of())), List.of(), id()), MANY),
        Arguments.argumentSet("a Pipe is seq(left, right)", (Supplier<Node>) () -> pipe(c(), fn0()), AT_MOST_ONE),
        Arguments.argumentSet("a Pipe after a NEVER right side", (Supplier<Node>) () -> label(0, comma(pipe(c(), brk(0)), c())), NONE),
        // The order rows: seq(MANY, NEVER) is NONE, but either side alone or seq(NEVER, MANY) is not.
        Arguments.argumentSet("a Pipe of a MANY left side and a NEVER right side is NONE", (Supplier<Node>) () -> label(0, comma(pipe(many(), brk(0)), c())), AT_MOST_ONE),
        Arguments.argumentSet("a Bind is seq(value, body)", (Supplier<Node>) () -> new Bind(AT, many(), 0, c()), MANY),
        Arguments.argumentSet(
            "a Bind of a MANY value and a NEVER body is NONE", (Supplier<Node>) () -> label(1, comma(new Bind(AT, many(), 0, brk(1)), c())), AT_MOST_ONE),
        Arguments.argumentSet(
            "a Binds of a MANY value and a NEVER body is NONE",
            (Supplier<Node>) () -> label(2, comma(new Binds(AT, List.of(many(), c()), List.of(0, 1), brk(2)), c())),
            AT_MOST_ONE),
        Arguments.argumentSet(
            "a Binds of a MANY value, then a NEVER one, is NONE",
            (Supplier<Node>) () -> label(2, comma(new Binds(AT, List.of(many(), brk(2)), List.of(0, 1), c()), c())),
            AT_MOST_ONE),
        Arguments.argumentSet(
            "an If of a MANY cond and NEVER branches is NONE", (Supplier<Node>) () -> label(0, comma(new If(AT, many(), brk(0), brk(0), v -> true), c())), AT_MOST_ONE),
        Arguments.argumentSet(
            "a Foreach of a MANY init and a NEVER each is NONE",
            (Supplier<Node>) () -> label(1, comma(new Foreach(AT, many(), 0, pipe(c(), new Advance(AT, 0, brk(1)))), c())),
            AT_MOST_ONE),
        Arguments.argumentSet(
            "a Reduce of a MANY init and a NEVER each is NONE",
            (Supplier<Node>) () -> label(1, comma(new Reduce(AT, many(), 0, pipe(c(), new Advance(AT, 0, brk(1)))), c())),
            AT_MOST_ONE),
        Arguments.argumentSet("a Binds of ONE values and body is ONE", (Supplier<Node>) () -> new Binds(AT, List.of(c(), c()), List.of(0, 1), id()), ONE),
        Arguments.argumentSet("a Binds is seq over its values, then its body", (Supplier<Node>) () -> new Binds(AT, List.of(c(), fn0()), List.of(0, 1), c()), AT_MOST_ONE),
        Arguments.argumentSet(
            "a Binds whose body is NEVER", (Supplier<Node>) () -> label(2, comma(new Binds(AT, List.of(c(), c()), List.of(0, 1), brk(2)), c())), NONE),
        Arguments.argumentSet("an If is seq(cond, join(then, else))", (Supplier<Node>) () -> new If(AT, c(), c(), fn0(), v -> true), AT_MOST_ONE),
        Arguments.argumentSet("an If on a MANY cond is MANY", (Supplier<Node>) () -> new If(AT, many(), c(), c(), v -> true), MANY),
        Arguments.argumentSet("a Foreach is seq(init, each)", (Supplier<Node>) () -> new Foreach(AT, c(), 0, pipe(many(), new Advance(AT, 0, id()))), MANY),
        Arguments.argumentSet("an Advance is its update's", (Supplier<Node>) () -> new Advance(AT, 0, fn0()), AT_MOST_ONE),
        Arguments.argumentSet("a Collect is ONE", (Supplier<Node>) () -> new Collect(AT, many(), (elements, length) -> null), ONE),
        Arguments.argumentSet(
            "a Collect of a NEVER body is NEVER", (Supplier<Node>) () -> label(0, comma(new Collect(AT, brk(0), (elements, length) -> null), c())), NONE),
        Arguments.argumentSet("a Reduce is seq(init, ONE)", (Supplier<Node>) () -> new Reduce(AT, c(), 0, pipe(many(), new Advance(AT, 0, fn0()))), ONE),
        Arguments.argumentSet("a Reduce of a MANY init is MANY", (Supplier<Node>) () -> new Reduce(AT, many(), 0, new Advance(AT, 0, id())), MANY),
        Arguments.argumentSet(
            "a Reduce whose each is NEVER, of a ONE init, is NEVER",
            (Supplier<Node>) () -> label(1, comma(new Reduce(AT, c(), 0, pipe(c(), new Advance(AT, 0, brk(1)))), c())),
            NONE),
        // The init may give no result; then the Reduce ends normally with none, and the Comma's right side runs.
        Arguments.argumentSet(
            "a Reduce whose each is NEVER, of an AT_MOST_ONE init, is NONE",
            (Supplier<Node>) () -> label(1, comma(new Reduce(AT, fn0(), 0, pipe(c(), new Advance(AT, 0, brk(1)))), c())),
            AT_MOST_ONE),
        Arguments.argumentSet("a Comma after a result is MANY", (Supplier<Node>) () -> comma(c(), c()), MANY),
        Arguments.argumentSet("a Comma after a NONE left side is its right side's", (Supplier<Node>) () -> comma(label(0, brk(0)), c()), ONE),
        Arguments.argumentSet("an Alt after an AT_MOST_ONE left side is join(ONE, right)", (Supplier<Node>) () -> new Alt(AT, fn0(), c(), v -> true), ONE),
        Arguments.argumentSet("an Alt after a ONE left side is join(ONE, right)", (Supplier<Node>) () -> new Alt(AT, c(), fn0(), v -> true), AT_MOST_ONE),
        Arguments.argumentSet("an Alt after a MANY left side is MANY", (Supplier<Node>) () -> new Alt(AT, many(), c(), v -> true), MANY),
        Arguments.argumentSet("a Try of an AT_MOST_ONE body is join(body, handler)", (Supplier<Node>) () -> new Try(AT, fn0(), c()), AT_MOST_ONE),
        Arguments.argumentSet("a Try of a MANY body is MANY", (Supplier<Node>) () -> new Try(AT, many(), c()), MANY),
        Arguments.argumentSet("a Label is join(body, NONE)", (Supplier<Node>) () -> label(0, c()), AT_MOST_ONE));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("rules")
  void eachKindFollowsItsRule(Supplier<Node> main, Cardinality expected) {
    Node root = main.get();

    assertThat(Analysed.of(root).of(-1, root)).as("the root's fact").isEqualTo(expected);
  }

  /** NEVER is the fixpoint's start and a Break's value, but a fact keeps it as NONE (ADR-0007 #112). */
  @Test
  void aNeverFactIsKeptAsNone() {
    Break b = brk(0);
    Label l = label(0, b);
    Analysed a = Analysed.of(l);

    assertThat(a.of(-1, b)).as("the Break").isEqualTo(NONE);
    assertThat(a.of(-1, l)).as("the Label").isEqualTo(NONE);
  }

  /** The parts of a node keep their own facts: an Advance its update's, inside a Reduce of another value. */
  @Test
  void everyNodeHasItsOwnFact() {
    Advance advance = new Advance(AT, 0, fn0());
    Intrinsic source = many();
    Reduce reduce = new Reduce(AT, c(), 0, pipe(source, advance));
    Analysed a = Analysed.of(reduce);

    assertThat(a.of(-1, reduce)).as("the Reduce").isEqualTo(ONE);
    assertThat(a.of(-1, source)).as("its source").isEqualTo(MANY);
    assertThat(a.of(-1, advance)).as("its Advance").isEqualTo(AT_MOST_ONE);
  }

  /**
   * {@code def f: if c then . else fn0 | f end; f}: from NEVER, the first round gives f AT_MOST_ONE through its then
   * branch, and the second confirms it; the Call in main takes it.
   */
  @Test
  void aRecursiveFunctionReachesItsFixpoint() {
    Call inside = call(0);
    Function f = function(0, new If(AT, c(), id(), pipe(fn0(), inside), v -> true));
    Call outside = call(0);
    Analysed a = Analysed.of(outside, f);

    assertThat(a.of(0, f.body())).as("f").isEqualTo(AT_MOST_ONE);
    assertThat(a.of(0, inside)).as("the recursive Call").isEqualTo(AT_MOST_ONE);
    assertThat(a.of(-1, outside)).as("the Call in main").isEqualTo(AT_MOST_ONE);
  }

  /** {@code def f: f; f}: f only recurses, so it stays at NEVER, kept as NONE; {@code def g: 1, g; g} is MANY. */
  @Test
  void aFunctionThatOnlyRecursesIsNone() {
    Call only = call(0);
    Analysed never = Analysed.of(call(0), function(0, only));
    assertThat(never.of(0, only)).as("f in f").isEqualTo(NONE);

    Call outside = call(0);
    Analysed many = Analysed.of(outside, function(0, comma(c(), call(0))));
    assertThat(many.of(-1, outside)).as("g in main").isEqualTo(MANY);
  }

  /** ev and od call each other: one group, whose fixpoint gives both AT_MOST_ONE. */
  @Test
  void aGroupOfTwoSharesItsFixpoint() {
    Function ev = function(0, new If(AT, c(), c(), pipe(fn0(), call(1)), v -> true));
    Function od = function(0, new If(AT, c(), c(), pipe(fn0(), call(0)), v -> true));
    Analysed a = Analysed.of(call(0), ev, od);

    assertThat(a.of(0, ev.body())).as("ev").isEqualTo(AT_MOST_ONE);
    assertThat(a.of(1, od.body())).as("od").isEqualTo(AT_MOST_ONE);
  }

  /** A ParamCall is MANY, and so is the function that runs one and each Call of it, whatever closure it passes. */
  @Test
  void aParamCallIsMany() {
    ParamCall p = new ParamCall(AT, 0);
    Call outside = call(0, c());
    Analysed a = Analysed.of(outside, function(1, p));

    assertThat(a.of(0, p)).as("the ParamCall").isEqualTo(MANY);
    assertThat(a.of(-1, outside)).as("the Call").isEqualTo(MANY);
  }

  /**
   * A callee is done before its caller whichever place each has: fn0 calls fn1, and fn3 calls fn2, each callee ONE and
   * no call recursive.
   */
  @Test
  void aCalleeIsDoneBeforeItsCaller() {
    Call first = call(0);
    Call second = call(3);
    Analysed a = Analysed.of(comma(first, second), function(0, call(1)), function(0, c()), function(0, c()), function(0, call(2)));

    assertThat(a.of(-1, first)).as("fn0, calling the later fn1").isEqualTo(ONE);
    assertThat(a.of(-1, second)).as("fn3, calling the earlier fn2").isEqualTo(ONE);
  }

  /** The conservative facts are MANY on every node of every body (ADR-0018). */
  @Test
  void conservativeFactsAreMany() {
    Node main = comma(c(), call(0));
    Program program = new Program(main, List.of(function(0, label(0, brk(0)))));
    Numbering numbering = Numbering.of(program);
    Facts facts = Facts.conservative(numbering);

    assertThat(numbering.nodes(-1)).hasSize(3).allSatisfy(n -> assertThat(facts.cardinality(-1, numbering.id(n))).isEqualTo(MANY));
    assertThat(numbering.nodes(0)).hasSize(2).allSatisfy(n -> assertThat(facts.cardinality(0, numbering.id(n))).isEqualTo(MANY));
  }

  /** Each body numbers its nodes from 0 in pre-order, a node before its parts in their order (GLOSSARY "Freeze id"). */
  @Test
  void eachBodyIsNumberedInPreOrder() {
    Const one = c();
    Const two = c();
    Const three = c();
    Pipe pipe = pipe(two, three);
    Comma main = comma(one, pipe);
    Const body = c();
    Numbering numbering = Numbering.of(new Program(main, List.of(function(0, body))));

    assertThat(numbering.nodes(-1)).as("main by id").containsExactly(main, one, pipe, two, three);
    assertThat(numbering.id(three)).as("the last Const").isEqualTo(4);
    assertThat(numbering.nodes(0)).as("fn0 by id").containsExactly(body);
    assertThat(numbering.id(body)).as("fn0's root").isZero();
  }

  /** The map is by identity: an equal node of no body has no id. */
  @Test
  void aNodeOfNoBodyHasNoId() {
    Numbering numbering = Numbering.of(new Program(c(), List.of()));

    assertThatIllegalArgumentException().isThrownBy(() -> numbering.id(c())).withMessageContaining("no node of the numbered program");
  }

  /**
   * fn0 and fn1 call each other, fn2 calls fn0 and nothing calls fn2: two groups, the callee's first, and the Calls in
   * a closure fn2 writes count as fn2's.
   */
  @Test
  void groupsAreCalleeFirst() {
    Function f0 = function(0, call(1));
    Function f1 = function(0, call(0));
    Function f2 = function(0, call(3, call(0)));
    Function f3 = function(1, new ParamCall(AT, 0));
    Program program = new Program(id(), List.of(f0, f1, f2, f3));
    CallGraph graph = CallGraph.of(program, Numbering.of(program));

    assertThat(graph.groups()).as("groups").isEqualTo(3);
    assertThat(graph.group(0)).as("fn0's group").isEqualTo(graph.group(1));
    assertThat(graph.members(graph.group(0))).as("fn0's group's members").containsExactlyInAnyOrder(0, 1);
    assertThat(graph.group(3)).as("fn3, called by fn2").isLessThan(graph.group(2));
    assertThat(graph.group(0)).as("fn0, called in fn2's closure").isLessThan(graph.group(2));
  }
}
