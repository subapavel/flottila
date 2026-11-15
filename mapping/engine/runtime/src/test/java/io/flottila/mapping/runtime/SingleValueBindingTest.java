package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrType;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The interpreter binds the single-value function of a {@code Lift}'s body by its catalogue name
 * once, when it compiles (ADR-0025): a name no single-value function has is a compiler bug, as
 * lowering calls only functions of the catalogue.
 */
class SingleValueBindingTest {

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final Name ORDER = new Name(NS, "Order");
  private static final Name NOTE = new Name("", "Note");
  private static final AuthoringId T1 = new AuthoringId("t1");
  private static final AuthoringId F1 = new AuthoringId("f1");

  /**
   * {@code <Note>{value}</Note>} per slot of a Lift over Note whose body calls {@code name} over
   * in1 and then the {@code params}.
   */
  private static TargetDocument noteOverACallOf(String name, String... params) {
    List<IrType> types = new ArrayList<>(List.of(AtomicType.STRING));
    List<Expr> args = new ArrayList<>(List.of(new VarRef("in1", AtomicType.STRING, F1)));
    for (String param : params) {
      types.add(AtomicType.STRING);
      args.add(new Literal(param, F1));
    }
    FunctionRef function = new FunctionRef(name, types, AtomicType.STRING);
    Lift lift =
        new Lift(
            List.of(new SourceQueue(List.of(ORDER, NOTE), 1, new AuthoringId("a1"))),
            List.of("in1"),
            new FunCall(function, args, F1),
            F1);
    return new TargetDocument(
        "ns0",
        ORDER,
        "root",
        List.of(
            new TargetSlots(
                lift,
                new VarRef("root", AtomicType.INTEGER, T1),
                0,
                Long.MAX_VALUE,
                "slot",
                "v",
                new ElementConstructor(
                    "",
                    NOTE,
                    List.of(new TextConstructor(new VarRef("v", AtomicType.STRING, T1), T1)),
                    T1),
                T1)));
  }

  @Test
  void aSingleValueFunctionTheRuntimeLacksIsACompilerBug() {
    TargetDocument unknown = noteOverACallOf("noSuchFunction", "X");
    // witness: the IR is well-formed, and the same Lift over replaceString compiles (replaceValue
    // was the sample until #216 made it a context function, F-45)
    IrVerifier.verify(unknown);
    assertThat(Interpreter.compile(noteOverACallOf("replaceString", "a", "X"), false)).isNotNull();

    assertThatThrownBy(() -> Interpreter.compile(unknown, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noSuchFunction");
  }

  /**
   * ADR-0026 widens the single-value binding to take the call's box, as a queue function's does,
   * before the family tickets use it. What is seen here is that the widened signature still binds
   * by name: replaceString replaces its search in the value. No single-value function of #71 raises
   * a data error, so nothing here can see the box; the witness that a binding raises at its box
   * comes with the first single-value function that raises one (#72). The method keeps its name;
   * replaceValue was the sample until #216 made it a context function (F-45).
   */
  @Test
  void theWidenedBindingBindsReplaceValue() {
    Node.OfString value = frame -> "vav";
    Node.OfString search = frame -> "a";
    Node.OfString replacement = frame -> "X";

    Node.OfString bound =
        SingleValueLibrary.bind("replaceString", F1, List.of(value, search, replacement));

    assertThat(bound.eval(null)).isEqualTo("vXv");
  }
}
