package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of Call, ParamCall and Program at the IR seam (ADR-0005 "Verifier", ADR-0045 and its #107 amendment), over
 * plain objects (founding §14), and their run. A Call names a function of the program's list and passes as many
 * closures and values as the function declares; its values are atomic (Identity, Var or Const); its closures are nodes
 * of the caller's scope, so they read the caller's slots and break to the caller's labels. A ParamCall runs closure
 * parameter j of the function it is in, so it appears only inside a function and only below that function's closure
 * count. In a function, slots 0 .. values - 1 are its value parameters, in scope in its body and bound by no node, and it
 * sees no slot of its caller. A label is passed as a value, its token, and a Break in the callee names the token's
 * parameter slot. No node object is used twice in the program, across functions too. A broken rule is an
 * IllegalStateException from compile, as for IrVerifierTest and LabelVerifierTest.
 *
 * <p>This class holds #107's verifier cases in a file of its own: until the types exist it does not compile, and
 * IrVerifierTest stays as it is. Shapes the test-author fixed from ADR-0045's #107 amendment (no draft code reached the
 * red): {@code Call(Source, int fn, List<Node> closures, List<Node> values)} and {@code ParamCall(Source, int param)},
 * node kinds of {@code query.ir}; {@code Function(Source, int closures, int values, Node body)} and {@code Program(Node
 * main, List<Function> functions)} in {@code query.ir}, neither a node kind; {@code Compiler.compile(Program, int
 * parameters, CompileMode)}, main's parameter slots as in {@code Compiler.compile(Node, int, CompileMode)}. A caller
 * passes a label's token as {@code Var} of the Label's slot, the one atomic node that reads a slot; LabelVerifierTest
 * still rejects such a Var anywhere else.
 */
class CallVerifierTest {
  private static final Source AT = new Source(0, 1);

  private static final Fn1 ID = (state, in, a) -> a;

  private static Executable compile(Program program) {
    return Compiler.compile(program, 0, CompileMode.OPTIMISED);
  }

  private static Program program(Node main, Function... functions) {
    return new Program(main, List.of(functions));
  }

  private static Function function(int closures, int values, Node body) {
    return new Function(AT, closures, values, body);
  }

  private static Call call(int fn, List<Node> closures, List<Node> values) {
    return new Call(AT, fn, closures, values);
  }

  private static ParamCall param(int j) {
    return new ParamCall(AT, j);
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  private static Node comma(Node... nodes) {
    Node result = nodes[nodes.length - 1];
    for (int i = nodes.length - 2; i >= 0; i--) {
      result = new Comma(AT, nodes[i], result);
    }
    return result;
  }

  private static Intrinsic id(Node arg) {
    return new Intrinsic(AT, new Operation("id", ID), List.of(arg));
  }

  /** The list of its arguments, any number of them. */
  private static Intrinsic list(Node... args) {
    FnN all = (state, in, a) -> List.of(a);
    return new Intrinsic(AT, new Operation("list", all), List.of(args));
  }

  /** The results of {@code program} on {@code in}; the sink never stops, and the run must report that they ran out. */
  private static List<Object> results(Program program, Object in) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        compile(program)
            .run(
                new RunState(),
                new Object[0],
                in,
                v -> {
                  emitted.add(v);
                  return true;
                });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @Test
  void aValueParameterIsReadInItsBody() {
    Object a = new Object();
    Object b = new Object();
    Object in = new Object();
    // f($0; $1): list($0; $1), called with two constants.
    assertThat(results(program(call(0, List.of(), List.of(c(a), c(b))), function(0, 2, list(var(0), var(1)))), in))
        .as("results of f(a; b) with f($0; $1) = list($0; $1)")
        .containsExactly(List.of(a, b));
    // Each atomic kind as a value: the caller's Var, its input (Identity) and a Const.
    Node main = new Bind(AT, c(a), 0, call(0, List.of(), List.of(var(0), new Identity(AT), c(b))));
    assertThat(results(program(main, function(0, 3, list(var(0), var(1), var(2)))), in))
        .as("results of a as $0 | f($0; .; b)")
        .containsExactly(List.of(a, in, b));
  }

  @Test
  void aFunctionSeesNoSlotOfItsCaller() {
    Object a = new Object();
    // a as $0 | f with f reading slot 0, which is no parameter of f and which f does not bind.
    Node main = new Bind(AT, c(a), 0, call(0, List.of(), List.of()));
    assertThatIllegalStateException().as("compile of a function that reads its caller's slot").isThrownBy(() -> compile(program(main, function(0, 0, id(var(0))))));
    // Witness: passed as a value, the same slot reaches it.
    Node passing = new Bind(AT, c(a), 0, call(0, List.of(), List.of(var(0))));
    assertThat(results(program(passing, function(0, 1, id(var(0)))), new Object())).as("results of a as $0 | f($0)").containsExactly(a);
  }

  @Test
  void rejectsABindOfAValueParameter() {
    Object a = new Object();
    Object b = new Object();
    // f($0): b as $0 | $0.
    Function binds = function(0, 1, new Bind(AT, c(b), 0, id(var(0))));
    assertThatIllegalStateException().as("compile of a function that binds its value parameter").isThrownBy(() -> compile(program(call(0, List.of(), List.of(c(a))), binds)));
    // Witness: binding slot 1 instead runs, beside the parameter.
    Function beside = function(0, 1, new Bind(AT, c(b), 1, list(var(0), var(1))));
    assertThat(results(program(call(0, List.of(), List.of(c(a))), beside), new Object())).as("results of f(a) with f($0): b as $1 | list($0; $1)").containsExactly(List.of(a, b));
  }

  /**
   * The closure reads slot 1 and breaks to the Label of slot 0, both its caller's, while f binds a slot 1 of its own in
   * its own frame: f emits its own, then the closure emits the caller's, then the break ends f, the closure and the
   * Label's body, and the program goes on after the Label.
   */
  @Test
  void aClosureArgumentReadsTheCallersSlotAndBreaksToItsLabel() {
    Object a = new Object();
    Object own = new Object();
    Object after = new Object();
    Node closure = comma(var(1), new Break(AT, 0), c(new Object()));
    Node main = comma(new Label(AT, 0, new Bind(AT, c(a), 1, comma(call(0, List.of(closure), List.of()), c(new Object())))), c(after));
    Function f = function(1, 0, new Bind(AT, c(own), 1, comma(var(1), param(0), c(new Object()))));
    assertThat(results(program(main, f), new Object())).as("results of label 0 | a as $1 | f($1, break 0, x), y; then after").containsExactly(own, a, after);
  }

  /** A closure that binds a slot of its own reads it beside the caller's slot, once per invocation. */
  @Test
  void aClosureArgumentThatBindsASlotReadsItAndTheCallersSlot() {
    Object a = new Object();
    Object b = new Object();
    Object own = new Object();
    Node closure = new Bind(AT, c(b), 2, list(var(1), var(2)));
    Node main = new Bind(AT, c(a), 1, call(0, List.of(closure), List.of()));
    Function f = function(1, 0, new Bind(AT, c(own), 1, comma(var(1), param(0), param(0))));
    assertThat(results(program(main, f), new Object())).as("results of a as $1 | f(b as $2 | list($1; $2))").containsExactly(own, List.of(a, b), List.of(a, b));
  }

  /** A ParamCall passed on as a closure is not wrapped (ADR-0045 #107): g runs f's closure. */
  @Test
  void aParamCallPassedOnRunsTheOuterClosure() {
    Object a = new Object();
    Function f = function(1, 0, call(1, List.of(param(0)), List.of()));
    Function g = function(1, 0, param(0));
    assertThat(results(program(call(0, List.of(c(a)), List.of()), f, g), new Object())).as("results of f(a) with f(h): g(h) and g(k): k").containsExactly(a);
  }

  /**
   * The caller passes its Label's token as a value; the callee's Break names that value parameter. It ends the callee
   * and the Label's body, and the program goes on after the Label. Passed on from f to g, the token still names the
   * caller's Label.
   */
  @Test
  void aBreakNamesALabelTokenParameter() {
    Object x = new Object();
    Object after = new Object();
    Node main = comma(new Label(AT, 0, comma(call(0, List.of(), List.of(var(0))), c(new Object()))), c(after));
    Function breaks = function(0, 1, comma(c(x), new Break(AT, 0), c(new Object())));
    assertThat(results(program(main, breaks), new Object())).as("results of label 0 | f($token), y; then after").containsExactly(x, after);

    Node through = comma(new Label(AT, 0, comma(call(0, List.of(), List.of(var(0))), c(new Object()))), c(after));
    Function passes = function(0, 1, call(1, List.of(), List.of(var(0))));
    Function breaksInG = function(0, 1, comma(c(x), new Break(AT, 0), c(new Object())));
    assertThat(results(program(through, passes, breaksInG), new Object())).as("results of the token passed on from f to g").containsExactly(x, after);
  }

  @Test
  void rejectsABreakOfASlotThatIsNoParameterOfItsFunction() {
    // f($0): break 1, where slot 1 is neither a value parameter nor a Label's in f.
    Node main = new Label(AT, 0, call(0, List.of(), List.of(var(0))));
    assertThatIllegalStateException().as("compile of a Break of slot 1 in a function of one value").isThrownBy(() -> compile(program(main, function(0, 1, new Break(AT, 1)))));
    // A token passed where no Label of its slot is around the Call.
    Node outside = comma(new Label(AT, 0, c(1)), call(0, List.of(), List.of(var(0))));
    assertThatIllegalStateException().as("compile of a token passed outside its Label").isThrownBy(() -> compile(program(outside, function(0, 1, new Break(AT, 0)))));
  }

  /**
   * A main parameter slot is no label token (ADR-0045 #107: only a function's value parameter is): main's Break of its
   * parameter slot 0 is rejected, as main has no Label of that slot.
   */
  @Test
  void rejectsABreakOfAMainParameterSlot() {
    assertThatIllegalStateException()
        .as("compile of a main of one parameter slot that breaks to it")
        .isThrownBy(() -> Compiler.compile(program(new Break(AT, 0)), 1, CompileMode.OPTIMISED));
  }

  @Test
  void rejectsAParamCallInMain() {
    Object a = new Object();
    assertThatIllegalStateException().as("compile of a main that is a ParamCall").isThrownBy(() -> compile(program(param(0))));
    assertThatIllegalStateException()
        .as("compile of a ParamCall in main beside a function of one closure")
        .isThrownBy(() -> compile(program(comma(param(0), call(0, List.of(c(a)), List.of())), function(1, 0, param(0)))));
    // A closure argument written in main is main's: a ParamCall there has no function to take its closure from.
    assertThatIllegalStateException().as("compile of a ParamCall as main's closure argument").isThrownBy(() -> compile(program(call(0, List.of(param(0)), List.of()), function(1, 0, param(0)))));
    // Witness: inside the function, the same ParamCall runs main's closure.
    assertThat(results(program(call(0, List.of(c(a)), List.of()), function(1, 0, param(0))), new Object())).as("results of f(a) with f(g): g").containsExactly(a);
  }

  @Test
  void rejectsAParamCallPastItsFunctionsClosures() {
    Object a = new Object();
    assertThatIllegalStateException().as("compile of ParamCall 1 in a function of one closure").isThrownBy(() -> compile(program(call(0, List.of(c(a)), List.of()), function(1, 0, param(1)))));
    assertThatIllegalStateException().as("compile of ParamCall 0 in a function of no closure").isThrownBy(() -> compile(program(call(0, List.of(), List.of()), function(0, 0, param(0)))));
    assertThatIllegalStateException().as("compile of ParamCall -1").isThrownBy(() -> compile(program(call(0, List.of(c(a)), List.of()), function(1, 0, param(-1)))));
    // Witness: the last closure of two runs.
    Object b = new Object();
    assertThat(results(program(call(0, List.of(c(a), c(b)), List.of()), function(2, 0, param(1))), new Object())).as("results of f(a; b) with f(g; h): h").containsExactly(b);
  }

  @Test
  void rejectsACallWithTheWrongClosureCount() {
    Object a = new Object();
    Function one = function(1, 0, param(0));
    assertThatIllegalStateException().as("compile of a call with no closure for one").isThrownBy(() -> compile(program(call(0, List.of(), List.of()), one)));
    assertThatIllegalStateException().as("compile of a call with two closures for one").isThrownBy(() -> compile(program(call(0, List.of(c(a), c(a)), List.of()), function(1, 0, param(0)))));
    // Witness: one closure for one.
    assertThat(results(program(call(0, List.of(c(a)), List.of()), function(1, 0, param(0))), new Object())).as("results of f(a) with f(g): g").containsExactly(a);
  }

  @Test
  void rejectsACallWithTheWrongValueCount() {
    Object a = new Object();
    assertThatIllegalStateException().as("compile of a call with no value for one").isThrownBy(() -> compile(program(call(0, List.of(), List.of()), function(0, 1, id(var(0))))));
    assertThatIllegalStateException().as("compile of a call with two values for one").isThrownBy(() -> compile(program(call(0, List.of(), List.of(c(a), c(a))), function(0, 1, id(var(0))))));
    // Witness: one value for one.
    assertThat(results(program(call(0, List.of(), List.of(c(a))), function(0, 1, id(var(0)))), new Object())).as("results of f(a) with f($0): $0").containsExactly(a);
  }

  @Test
  void rejectsANonAtomicValue() {
    Object a = new Object();
    Function f = function(0, 1, id(var(0)));
    assertThatIllegalStateException().as("compile of a Comma as a value").isThrownBy(() -> compile(program(call(0, List.of(), List.of(comma(c(a), c(a)))), f)));
    assertThatIllegalStateException()
        .as("compile of a Pipe as a value")
        .isThrownBy(() -> compile(program(call(0, List.of(), List.of(new Pipe(AT, c(a), new Identity(AT)))), function(0, 1, id(var(0))))));
    assertThatIllegalStateException().as("compile of an Intrinsic as a value").isThrownBy(() -> compile(program(call(0, List.of(), List.of(id(c(a)))), function(0, 1, id(var(0))))));
    // Witness: the Const alone (aValueParameterIsReadInItsBody runs Var and Identity).
    assertThat(results(program(call(0, List.of(), List.of(c(a))), function(0, 1, id(var(0)))), new Object())).as("results of f(a)").containsExactly(a);
  }

  @Test
  void rejectsAValueOutsideTheCallersScope() {
    Object a = new Object();
    assertThatIllegalStateException().as("compile of f($5) with no slot 5").isThrownBy(() -> compile(program(call(0, List.of(), List.of(var(5))), function(0, 1, id(var(0))))));
    assertThatIllegalStateException().as("compile of f(id($5)) with no slot 5").isThrownBy(() -> compile(program(call(0, List.of(id(var(5))), List.of()), function(1, 0, param(0)))));
    // Witness: slot 5 bound around the call.
    Node main = new Bind(AT, c(a), 5, call(0, List.of(), List.of(var(5))));
    assertThat(results(program(main, function(0, 1, id(var(0)))), new Object())).as("results of a as $5 | f($5)").containsExactly(a);
  }

  @Test
  void rejectsACallOfAFunctionOutOfRange() {
    Object a = new Object();
    assertThatIllegalStateException().as("compile of a call of function 1 of one").isThrownBy(() -> compile(program(call(1, List.of(), List.of()), function(0, 0, c(a)))));
    assertThatIllegalStateException().as("compile of a call of function -1").isThrownBy(() -> compile(program(call(-1, List.of(), List.of()), function(0, 0, c(a)))));
    assertThatIllegalStateException().as("compile of a call in a program of no function").isThrownBy(() -> compile(program(call(0, List.of(), List.of()))));
    // Witness: function 1 of two.
    Object b = new Object();
    assertThat(results(program(call(1, List.of(), List.of()), function(0, 0, c(a)), function(0, 0, c(b))), new Object())).as("results of a call of function 1").containsExactly(b);
  }

  @Test
  void rejectsANodeUsedInTwoFunctions() {
    Object a = new Object();
    Const shared = c(a);
    assertThatIllegalStateException().as("compile of a node in main and in f").isThrownBy(() -> compile(program(comma(shared, call(0, List.of(), List.of())), function(0, 0, shared))));
    Const twice = c(a);
    Function f = function(0, 0, comma(twice, call(1, List.of(), List.of())));
    assertThatIllegalStateException().as("compile of a node in f and in g").isThrownBy(() -> compile(program(call(0, List.of(), List.of()), f, function(0, 0, twice))));
    Const argument = c(a);
    assertThatIllegalStateException().as("compile of one node as two closures").isThrownBy(() -> compile(program(call(0, List.of(argument, argument), List.of()), function(2, 0, param(0)))));
    // Witness: the same shapes with nodes of their own.
    assertThat(results(program(comma(c(a), call(0, List.of(), List.of())), function(0, 0, c(a))), new Object())).as("results of a, f with f: a").containsExactly(a, a);
  }
}
