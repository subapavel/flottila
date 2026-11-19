package dev.rail8.jq.query.compile;

import static dev.rail8.jq.query.compile.Specialising.at;
import static dev.rail8.jq.query.compile.Specialising.countdown;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.analysis.Numbering;
import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Tracking;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.InertPaths;
import dev.rail8.jq.query.run.PathFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The specialisation pass over path mode's parts: a copy keeps each bind's tracking, each Update's kind and the
 * program's one format, both in the callee's own nodes and in an inlined argument, and two arguments that differ only in
 * a tracking or an Update's kind are two keys, so two copies.
 */
class SpecialiserPathTest {
  private static final PathFormat PATHS = new InertPaths();

  /** What path mode reads of {@code node}: its kind, with a bind's tracking and an Update's kind; null for no mark. */
  private static String mark(Node node) {
    return switch (node) {
      case Bind b -> "Bind " + b.tracking();
      case Binds b -> "Binds " + b.trackings();
      case AltBind a -> "AltBind " + a.tracking();
      case PathOf p -> {
        assertThat(p.paths()).as("a PathOf's format").isSameAs(PATHS);
        yield "PathOf";
      }
      case Update u -> {
        assertThat(u.paths()).as("an Update's format").isSameAs(PATHS);
        yield "Update " + u.kind();
      }
      default -> null;
    };
  }

  /** The marks of the body {@code body} of {@code program}, in freeze order. */
  private static List<String> marks(Program program, int body) {
    List<String> marks = new ArrayList<>();
    for (Node node : Numbering.of(program).nodes(body)) {
      String m = mark(node);
      if (m != null) {
        marks.add(m);
      }
    }
    return marks;
  }

  /**
   * An argument with every mark, its slots from {@code s}: {@code (. as $a | $a), foreach . as $i (.; $i), (. as [$b,
   * $c] | $c), (. as $v ?// . | .), path(.), (. = 1)}, the first Bind PATH, the Foreach's source ITEM, the Binds PATH
   * then VALUE, the AltBind PATH.
   */
  private static Node argument(int s) {
    Node bind = new Bind(at(1), new Identity(at(1)), Tracking.PATH, s, new Var(at(1), s));
    Node foreach = new Foreach(at(2), new Identity(at(2)), s + 1, new Bind(at(2), new Identity(at(2)), Tracking.ITEM, s + 2, new Advance(at(2), s + 1, new Var(at(2), s + 2))));
    Node binds = new Binds(at(3), List.of(new Identity(at(3)), new Identity(at(3))), List.of(Tracking.PATH, Tracking.VALUE), List.of(s + 3, s + 4), new Var(at(3), s + 4));
    Node altBind = new AltBind(at(4), new Identity(at(4)), Tracking.PATH, s + 5, List.of(new Alternative(new Identity(at(4)), List.of())), List.of(), new Identity(at(4)));
    Node paths = new Comma(at(5), new PathOf(at(5), new Identity(at(5)), PATHS), new Update(at(6), Update.Kind.ASSIGN, new Identity(at(6)), new Const(at(6), 1), PATHS));
    return new Comma(at(7), bind, new Comma(at(7), foreach, new Comma(at(7), binds, new Comma(at(7), altBind, paths))));
  }

  @Test
  void aCopyKeepsEveryMark() {
    // def k(f): if . then f, (dec | k(f)) else . end, with path(f) and (f |= .) beside f; main k(argument).
    Node own = new Comma(at(10), new PathOf(at(10), new ParamCall(at(10), 0), PATHS), new Update(at(11), Update.Kind.MODIFY, new ParamCall(at(11), 0), new Identity(at(11)), PATHS));
    Node body = new Comma(at(12), own, countdown(0, at(12)));
    Program program = new Program(new Call(at(20), 0, List.of(argument(0)), List.of()), List.of(new Function(at(9), 1, 0, body)));

    Specialiser.Result result = Specialiser.specialise(program, 0, IrVerifier.verify(program, 0), Specialiser.SPECIALISE_CAP);

    assertThat(result.program().functions()).as("k and its copy").hasSize(2);
    List<String> argument = marks(new Program(argument(0), List.of()), -1);
    assertThat(argument).as("premise: the argument's marks").containsExactly("Bind PATH", "Bind ITEM", "Binds [PATH, VALUE]", "AltBind PATH", "PathOf", "Update ASSIGN");
    // The copy holds k's own marks, then the argument's once per ParamCall the copy inlines: in path(f), in f |= ., and
    // the countdown's f; the bare f passed on leaves the Call.
    List<String> expected = new ArrayList<>(List.of("PathOf"));
    expected.addAll(argument);
    expected.add("Update MODIFY");
    expected.addAll(argument);
    expected.addAll(argument);
    assertThat(marks(result.program(), 1)).as("the copy's marks in freeze order").containsExactlyElementsOf(expected);
    IrVerifier.verify(result.program(), 0);
  }

  /** Pairs of arguments, their slots from the given one, that differ only in one mark. */
  static Stream<Arguments> differing() {
    return Stream.of(
        pair("a Bind's tracking", s -> new Bind(at(1), new Identity(at(1)), Tracking.VALUE, s, new Var(at(1), s)), s -> new Bind(at(1), new Identity(at(1)), Tracking.PATH, s, new Var(at(1), s))),
        pair(
            "a Binds' trackings",
            s -> new Binds(at(1), List.of(new Identity(at(1)), new Identity(at(1))), List.of(Tracking.PATH, Tracking.VALUE), List.of(s, s + 1), new Var(at(1), s)),
            s -> new Binds(at(1), List.of(new Identity(at(1)), new Identity(at(1))), List.of(Tracking.VALUE, Tracking.PATH), List.of(s, s + 1), new Var(at(1), s))),
        pair(
            "an AltBind's tracking",
            s -> new AltBind(at(1), new Identity(at(1)), Tracking.VALUE, s, List.of(new Alternative(new Identity(at(1)), List.of())), List.of(), new Identity(at(1))),
            s -> new AltBind(at(1), new Identity(at(1)), Tracking.PATH, s, List.of(new Alternative(new Identity(at(1)), List.of())), List.of(), new Identity(at(1)))),
        pair(
            "a Foreach source's tracking",
            s -> new Foreach(at(1), new Identity(at(1)), s, new Bind(at(1), new Identity(at(1)), Tracking.PATH, s + 1, new Advance(at(1), s, new Var(at(1), s + 1)))),
            s -> new Foreach(at(1), new Identity(at(1)), s, new Bind(at(1), new Identity(at(1)), Tracking.ITEM, s + 1, new Advance(at(1), s, new Var(at(1), s + 1))))),
        pair(
            "an Update's kind",
            s -> new Update(at(1), Update.Kind.MODIFY, new Identity(at(1)), new Const(at(1), 1), PATHS),
            s -> new Update(at(1), Update.Kind.ASSIGN, new Identity(at(1)), new Const(at(1), 1), PATHS)));
  }

  private static Arguments pair(String name, IntFunction<Node> first, IntFunction<Node> second) {
    return Arguments.argumentSet(name, first, second);
  }

  /** Main {@code k(first), k(second)} over the countdown k: its specialised program. */
  private static Program specialised(Node first, Node second) {
    Node main = new Comma(at(30), new Call(at(31), 0, List.of(first), List.of()), new Call(at(32), 0, List.of(second), List.of()));
    Program program = new Program(main, List.of(new Function(at(0), 1, 0, countdown(0, at(0)))));
    return Specialiser.specialise(program, 0, IrVerifier.verify(program, 0), Specialiser.SPECIALISE_CAP).program();
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("differing")
  void argumentsThatDifferInOneMarkAreTwoKeys(IntFunction<Node> first, IntFunction<Node> second) {
    assertThat(specialised(first.apply(0), first.apply(10)).functions()).as("the same argument twice: one copy").hasSize(2);
    assertThat(specialised(first.apply(0), second.apply(10)).functions()).as("arguments that differ in one mark: two copies").hasSize(3);
  }
}
