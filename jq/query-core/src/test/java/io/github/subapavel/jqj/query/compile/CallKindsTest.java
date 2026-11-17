package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.Specialising.DEC;
import static io.github.subapavel.jqj.query.compile.Specialising.PLUS;
import static io.github.subapavel.jqj.query.compile.Specialising.TAG;
import static io.github.subapavel.jqj.query.compile.Specialising.TRUTH;
import static io.github.subapavel.jqj.query.compile.Specialising.at;
import static io.github.subapavel.jqj.query.compile.Specialising.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.analysis.CallKind;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.Specialising.N;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
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
import io.github.subapavel.jqj.query.ir.Var;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The call kinds of ADR-0047 #118 at the compile seam, {@link Executable#callKind(int, int)}: the recursive call of
 * {@code def k(f): if . then dec | k(arg) else f end}, main {@code k(.)}, in a tail position whose closure argument
 * passes condition 4 when every slot it reads (a Var, a Break, an AltBind's from-slots) is one it binds itself and it
 * holds no ParamCall. The call is TAIL_SELF then and RECURSIVE otherwise; main's call is PLAIN, and the conservative
 * compile stores no tail kind. Each program gives the conservative compile's results in the optimised one.
 */
class CallKindsTest {
  private static final Object IN = new N(2);

  private static Node plus(Node a, Node b) {
    return new Intrinsic(at(0), PLUS, List.of(a, b));
  }

  /** {@code if . then dec | k(arg) else f end}, k function 0 with one closure and no value. */
  private static Node countdown(Node arg) {
    Node again = new Pipe(at(1), new Intrinsic(at(1), DEC, List.of()), new Call(at(2), 0, List.of(arg), List.of()));
    return new If(at(3), new Identity(at(3)), again, new ParamCall(at(4), 0), TRUTH);
  }

  /** The program of k's body {@code body}, main {@code k(.)}. */
  private static Program program(Node body) {
    return new Program(new Call(at(9), 0, List.of(new Identity(at(9))), List.of()), List.of(new Function(at(0), 1, 0, body)));
  }

  static Stream<Arguments> cases() {
    // Each closure binds slots of k's body from 0, which k binds nowhere else.
    Node bind = new Bind(at(5), new Identity(at(5)), 0, new Var(at(5), 0));
    Node binds = new Binds(at(5), List.of(new Identity(at(5)), new Const(at(5), new N(1))), List.of(0, 1), plus(new Var(at(5), 0), new Var(at(5), 1)));
    Node label = new Label(at(5), 0, new Comma(at(5), new Identity(at(5)), new Break(at(5), 0)));
    Node reduce = new Reduce(at(5), new Identity(at(5)), 0, new Bind(at(5), new Const(at(5), new N(1)), 1, new Advance(at(5), 0, plus(new Identity(at(5)), new Var(at(5), 1)))));
    Node foreach = new Foreach(at(5), new Identity(at(5)), 0, new Bind(at(5), new Const(at(5), new N(1)), 1, new Advance(at(5), 0, plus(new Identity(at(5)), new Var(at(5), 1)))));
    Alternative own = new Alternative(new Bind(at(5), new Var(at(5), 0), 1, new Identity(at(5))), List.of(1));
    Alternative none = new Alternative(new Identity(at(5)), List.of(Alternative.NULL));
    Node altBind = new AltBind(at(5), new Identity(at(5)), 0, List.of(own, none), List.of(2), new Var(at(5), 2));
    return Stream.of(
        Arguments.argumentSet("a Bind's own slot", countdown(bind), CallKind.TAIL_SELF),
        Arguments.argumentSet("a Binds' own slots", countdown(binds), CallKind.TAIL_SELF),
        Arguments.argumentSet("a Break to its own Label", countdown(label), CallKind.TAIL_SELF),
        Arguments.argumentSet("a Reduce's own slots", countdown(reduce), CallKind.TAIL_SELF),
        Arguments.argumentSet("a Foreach's own slots", countdown(foreach), CallKind.TAIL_SELF),
        Arguments.argumentSet("an AltBind's own from-slots", countdown(altBind), CallKind.TAIL_SELF),
        Arguments.argumentSet("a free slot: . as $x | k($x)", new Bind(at(6), new Identity(at(6)), 0, countdown(new Var(at(7), 0))), CallKind.RECURSIVE),
        Arguments.argumentSet("a ParamCall inside: k(f | tag)", countdown(new Pipe(at(7), new ParamCall(at(7), 0), new Intrinsic(at(7), TAG, List.of(new Identity(at(7)))))), CallKind.RECURSIVE),
        Arguments.argumentSet(
            "a many-valued condition: if (., .) then dec | k(.) else f end",
            new If(at(3), new Comma(at(3), new Identity(at(3)), new Identity(at(3))), new Pipe(at(1), new Intrinsic(at(1), DEC, List.of()), new Call(at(2), 0, List.of(new Identity(at(2))), List.of())), new ParamCall(at(4), 0), TRUTH),
            CallKind.RECURSIVE),
        Arguments.argumentSet("a Binds of one-valued values around dec | k(.)", binds(new Const(at(6), new N(1))), CallKind.TAIL_SELF),
        Arguments.argumentSet("a Binds with a many-valued value around dec | k(.)", binds(new Comma(at(6), new Const(at(6), new N(1)), new Const(at(6), new N(1)))), CallKind.RECURSIVE));
  }

  /**
   * {@code if . then (., second) as [$0, $1] | dec | k(.) else f end}: a Binds of slots 0 and 1, the identity first and
   * {@code second} second, around the recursive call; a tail position when every value is at most one.
   */
  private static Node binds(Node second) {
    Node again = new Pipe(at(1), new Intrinsic(at(1), DEC, List.of()), new Call(at(2), 0, List.of(new Identity(at(2))), List.of()));
    Node body = new Binds(at(6), List.of(new Identity(at(6)), second), List.of(0, 1), again);
    return new If(at(3), new Identity(at(3)), body, new ParamCall(at(4), 0), TRUTH);
  }

  /** The kind of each Call of {@code body}, in freeze-id order, and null for every other node. */
  private static List<CallKind> kinds(Executable executable, int body) {
    Numbering numbering = executable.frozen().numbering();
    List<CallKind> kinds = new ArrayList<>();
    List<Node> nodes = numbering.nodes(body);
    for (int id = 0; id < nodes.size(); id++) {
      CallKind kind = executable.callKind(body, id);
      assertThat(kind == null).as("whether node %d of body %d, %s, has no kind", id, body, nodes.get(id)).isEqualTo(!(nodes.get(id) instanceof Call));
      if (kind != null) {
        kinds.add(kind);
      }
    }
    return kinds;
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("cases")
  void theRecursiveCallHasItsKind(Node body, CallKind kind) {
    Program program = program(body);
    Executable optimised = Compiler.compile(program, 0, CompileMode.OPTIMISED);
    Executable conservative = Compiler.compile(program, 0, CompileMode.CONSERVATIVE);
    assertThat(optimised.frozen().program().functions()).as("premise: k alone, no copy").hasSize(1);
    assertThat(kinds(optimised, -1)).as("main's call under OPTIMISED").containsExactly(CallKind.PLAIN);
    assertThat(kinds(optimised, 0)).as("k's call under OPTIMISED").containsExactly(kind);
    assertThat(kinds(conservative, -1)).as("main's call under CONSERVATIVE").containsExactly(CallKind.PLAIN);
    assertThat(kinds(conservative, 0)).as("k's call under CONSERVATIVE").containsExactly(CallKind.RECURSIVE);

    Outcome o = run(optimised, IN);
    Outcome c = run(conservative, IN);
    assertThat(c.results()).as("premise: k has results").isNotEmpty();
    assertThat(o.results()).as("the optimised results against CONSERVATIVE").isEqualTo(c.results());
    assertThat(o.thrown()).as("what the optimised run threw").isEqualTo(c.thrown());
  }
}
