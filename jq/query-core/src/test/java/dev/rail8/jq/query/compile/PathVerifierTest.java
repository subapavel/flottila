package dev.rail8.jq.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Facts;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Reduce;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Tracking;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.InertPaths;
import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.PathFormat;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The IR rules of path mode, checked by the verifier before any backend sees a tree: a program's PathOf and Update
 * nodes share one format instance, an assign's right side is atomic, ITEM tracking is only on the source Bind of a
 * Foreach, a Binds has one tracking per value, an operation's path rule matches its call form both ways, and the slot
 * and tree rules hold inside a PathOf's body and an Update's sides.
 */
class PathVerifierTest {
  private static final Source AT = new Source(0, 1);

  private static final PathFormat PATHS = new InertPaths();

  /**
   * Verifies {@code program}, whose main tree has no parameter; a broken rule throws. Returns the slots a run of each
   * body needs, main's first.
   */
  private static int[] verify(Program program) {
    return IrVerifier.verify(program, 0).slots();
  }

  private static int[] verify(Node main) {
    return verify(new Program(main, List.of()));
  }

  private static Identity id() {
    return new Identity(AT);
  }

  private static Const c() {
    return new Const(AT, 1);
  }

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  private static PathOf pathOf(Node body) {
    return new PathOf(AT, body, PATHS);
  }

  private static Update update(Update.Kind kind, Node lhs, Node rhs) {
    return new Update(AT, kind, lhs, rhs, PATHS);
  }

  /** {@code foreach src as $v (.; $v)} with the source Bind's tracking {@code tracking}: state slot 0, item slot 1. */
  private static Foreach foreach(Tracking tracking) {
    return new Foreach(AT, id(), 0, new Bind(AT, c(), tracking, 1, new Advance(AT, 0, var(1))));
  }

  @Test
  void aPathOfAndUpdatesOfOneFormatVerify() {
    Node main = new Comma(AT, pathOf(id()), new Comma(AT, update(Update.Kind.MODIFY, id(), c()), update(Update.Kind.ASSIGN, id(), c())));
    Function f = new Function(AT, 0, 0, pathOf(id()));

    assertThat(verify(new Program(main, List.of(f)))).as("one format in main and in a function: the slots of each").containsExactly(0, 0);
  }

  @Test
  void twoFormatsInOneProgramAreRejected() {
    Node main = new Comma(AT, pathOf(id()), new PathOf(AT, id(), new InertPaths()));
    assertThatIllegalStateException().as("two formats in main").isThrownBy(() -> verify(main)).withMessageContaining("format");

    Function f = new Function(AT, 0, 0, new Update(AT, Update.Kind.MODIFY, id(), c(), new InertPaths()));
    assertThatIllegalStateException().as("a function's format against main's").isThrownBy(() -> verify(new Program(pathOf(id()), List.of(f)))).withMessageContaining("format");
  }

  @Test
  void anAssignsRightSideIsAtomic() {
    for (Supplier<Node> atomic : List.<Supplier<Node>>of(PathVerifierTest::id, PathVerifierTest::c, () -> var(0))) {
      Node main = new Bind(AT, c(), 0, update(Update.Kind.ASSIGN, id(), atomic.get()));
      assertThat(verify(main)).as("an assign of %s: its slots", atomic.get()).containsExactly(1);
    }
    assertThatIllegalStateException()
        .as("an assign of a Pipe")
        .isThrownBy(() -> verify(update(Update.Kind.ASSIGN, id(), new Pipe(AT, id(), c()))))
        .withMessageContaining("not atomic");
    assertThat(verify(update(Update.Kind.MODIFY, id(), new Pipe(AT, id(), c())))).as("a modify of a Pipe: its slots").containsExactly(0);
  }

  @Test
  void itemIsOnlyOnTheSourceBindOfAForeach() {
    assertThat(verify(foreach(Tracking.ITEM))).as("ITEM on a Foreach's source Bind: its slots").containsExactly(2);

    Map<String, Node> elsewhere = new LinkedHashMap<>();
    elsewhere.put("a Bind outside any loop", new Bind(AT, c(), Tracking.ITEM, 0, var(0)));
    elsewhere.put("a Reduce's source Bind", new Reduce(AT, id(), 0, new Bind(AT, c(), Tracking.ITEM, 1, new Advance(AT, 0, var(1)))));
    elsewhere.put(
        "a Bind inside a Foreach's source Bind",
        new Foreach(AT, id(), 0, new Bind(AT, c(), 1, new Bind(AT, var(1), Tracking.ITEM, 2, new Advance(AT, 0, var(2))))));
    elsewhere.put(
        "a Bind in a Foreach's init", new Foreach(AT, new Bind(AT, c(), Tracking.ITEM, 2, var(2)), 0, new Bind(AT, c(), 1, new Advance(AT, 0, var(1)))));
    elsewhere.put(
        "the Bind that a Foreach's each pipes",
        new Foreach(AT, id(), 0, new Pipe(AT, new Bind(AT, c(), Tracking.ITEM, 1, new Advance(AT, 0, var(1))), id())));
    elsewhere.put("a Binds value", new Binds(AT, List.of(c(), c()), List.of(Tracking.VALUE, Tracking.ITEM), List.of(0, 1), var(1)));
    elsewhere.put("an AltBind's value", new AltBind(AT, c(), Tracking.ITEM, 0, List.of(new Alternative(id(), List.of())), List.of(), id()));
    elsewhere.forEach(
        (where, node) -> assertThatIllegalStateException().as("ITEM on %s", where).isThrownBy(() -> verify(node)).withMessageContaining("ITEM"));
  }

  @Test
  void pathIsOnAnyBindValue() {
    // Each node with the slots it needs.
    Map<Node, Integer> marked = new LinkedHashMap<>();
    marked.put(new Bind(AT, c(), Tracking.PATH, 0, var(0)), 1);
    marked.put(foreach(Tracking.PATH), 2);
    marked.put(new Binds(AT, List.of(c(), c()), List.of(Tracking.PATH, Tracking.VALUE), List.of(0, 1), var(1)), 2);
    marked.put(new AltBind(AT, c(), Tracking.PATH, 0, List.of(new Alternative(id(), List.of())), List.of(), id()), 1);
    marked.forEach((node, slots) -> assertThat(verify(node)).as("PATH on %s: its slots", node).containsExactly(slots));
  }

  @Test
  void aBindsHasOneTrackingPerValue() {
    Node binds = new Binds(AT, List.of(c(), c()), List.of(Tracking.PATH), List.of(0, 1), var(1));

    assertThatIllegalStateException().isThrownBy(() -> verify(binds)).withMessageContaining("tracking");
  }

  @Test
  void theSlotAndTreeRulesHoldInsidePathOfAndUpdate() {
    assertThat(verify(new Bind(AT, c(), 0, new Comma(AT, pathOf(var(0)), update(Update.Kind.MODIFY, var(0), var(0))))))
        .as("a Var of an outer Bind in both sides: its slots")
        .containsExactly(1);
    assertThatIllegalStateException().as("a Var out of scope in a PathOf's body").isThrownBy(() -> verify(pathOf(var(0)))).withMessageContaining("scope");
    assertThatIllegalStateException().as("a Var out of scope in an lhs").isThrownBy(() -> verify(update(Update.Kind.MODIFY, var(0), id()))).withMessageContaining("scope");
    assertThatIllegalStateException().as("a Var out of scope in an rhs").isThrownBy(() -> verify(update(Update.Kind.ASSIGN, id(), var(0)))).withMessageContaining("scope");
    Node shared = c();
    assertThatIllegalStateException().as("one node on both sides").isThrownBy(() -> verify(update(Update.Kind.MODIFY, shared, shared))).withMessageContaining("used twice");
  }

  /** A one-result step of each arity and a many-result one, each also a PathStep; no case runs them. */
  private static final class Step0 implements Fn0, PathStep {
    @Override
    public Object apply(RunState state, Object in) {
      return in;
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return new FilterException(in);
    }
  }

  private static final class Step1 implements Fn1, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object a) {
      return in;
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return new FilterException(in);
    }
  }

  private static final class Step2 implements Fn2, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object a, Object b) {
      return in;
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return new FilterException(in);
    }
  }

  private static final class StepN implements FnN, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object[] args) {
      return in;
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return new FilterException(in);
    }
  }

  private static class PlainMany implements Many {
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
  }

  private static final class StepMany extends PlainMany implements PathStep {
    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return new FilterException(in);
    }
  }

  /** A call form under test, with the arguments its arity takes. */
  private record Form(String name, CallForm form, int arity) {}

  private static List<Form> forms() {
    return List.of(
        new Form("an Fn0", (Fn0) (state, in) -> in, 0),
        new Form("an Fn1", (Fn1) (state, in, a) -> in, 1),
        new Form("an Fn2", (Fn2) (state, in, a, b) -> in, 2),
        new Form("an FnN", (FnN) (state, in, args) -> in, 0),
        new Form("a Many", new PlainMany(), 0),
        new Form("a PathStep Fn0", new Step0(), 0),
        new Form("a PathStep Fn1", new Step1(), 1),
        new Form("a PathStep Fn2", new Step2(), 2),
        new Form("a PathStep FnN", new StepN(), 0),
        new Form("a PathStep Many", new StepMany(), 0));
  }

  /** Whether the verifier accepts an Intrinsic of {@code form} declaring {@code rule}. */
  private static boolean accepts(PathRule rule, Form form) {
    List<Node> args = new ArrayList<>();
    for (int k = 0; k < form.arity(); k++) {
      args.add(c());
    }
    try {
      verify(new Intrinsic(AT, new Operation("op", form.form(), new Facts(rule)), args));
      return true;
    } catch (IllegalStateException e) {
      assertThat(e).as("the refusal of %s on %s", rule, form.name()).hasMessageContaining("path rule");
      return false;
    }
  }

  /**
   * KEY, INDEX, DYNAMIC, SLICE and KEY_LIST if and only if the form is a PathStep Fn0, Fn1 or Fn2; ALL if and only if
   * it is a PathStep Many; NONE and SAME on every other form. A PathStep FnN needs no rule, so NONE and SAME take it.
   */
  @Test
  void aPathRuleMatchesItsCallFormBothWays() {
    List<String> accepted = new ArrayList<>();
    for (PathRule rule : PathRule.values()) {
      for (Form form : forms()) {
        if (accepts(rule, form)) {
          accepted.add(rule + " on " + form.name());
        }
      }
    }
    List<String> expected = new ArrayList<>();
    for (PathRule rule : List.of(PathRule.NONE, PathRule.SAME)) {
      for (String form : List.of("an Fn0", "an Fn1", "an Fn2", "an FnN", "a Many", "a PathStep FnN")) {
        expected.add(rule + " on " + form);
      }
    }
    for (PathRule rule : List.of(PathRule.KEY, PathRule.INDEX, PathRule.DYNAMIC, PathRule.SLICE, PathRule.KEY_LIST)) {
      for (String form : List.of("a PathStep Fn0", "a PathStep Fn1", "a PathStep Fn2")) {
        expected.add(rule + " on " + form);
      }
    }
    expected.add("ALL on a PathStep Many");

    assertThat(accepted).as("the pairs of rule and form the verifier accepts").containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void anOperationWithoutFactsIsUntracked() {
    assertThat(new Operation("op", (Fn0) (state, in) -> in).facts()).isEqualTo(Facts.UNTRACKED);
    assertThat(Facts.UNTRACKED.path()).isEqualTo(PathRule.NONE);
  }
}
