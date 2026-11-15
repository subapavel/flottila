package io.flottila.mapping.xslt.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
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
 * A {@code Lift} exports as {@code po:lift} over the sequence of its queues and an inline function
 * of {@code $in as xs:string*}, whose {@code let} binds slot variable k to {@code $in[k]} and which
 * returns the body (ADR-0025 since #51). The target field {@code OrderId} under {@code ns0:Order}
 * reads the Lift in the root's context; Saxon-HE runs such exports in {@code LiftAgreementTest}.
 */
class LiftExportTest {

  private static final Name ORDER = new Name("urn:flottila:corpus:skeleton", "Order");
  private static final Name ORDER_ID = new Name("", "OrderId");
  private static final Name NOTE = new Name("", "Note");
  private static final AuthoringId T1 = new AuthoringId("t1");
  private static final AuthoringId L1 = new AuthoringId("l1");
  private static final String ORDER_IDS =
      "po:source-queue(array:join(/s0:Order ! [OrderId ! string()]))";
  private static final String NOTES = "po:source-queue(array:join(/s0:Order ! [Note ! string()]))";
  // the sample single-value function with params; replaceValue was it until #216 made it a
  // context function (F-45)
  private static final FunctionRef REPLACE_STRING =
      new FunctionRef(
          "replaceString",
          List.of(AtomicType.STRING, AtomicType.STRING, AtomicType.STRING),
          AtomicType.STRING);

  private static SourceQueue underRoot(Name field, String box) {
    return new SourceQueue(List.of(ORDER, field), 1, new AuthoringId(box));
  }

  private static VarRef slotVar(String name) {
    return new VarRef(name, AtomicType.STRING, L1);
  }

  /** {@code <OrderId>} per slot of {@code queue}, with {@code content} beside its text. */
  private static TargetDocument orderIds(Expr queue, List<Expr> content) {
    List<Expr> all = new ArrayList<>();
    all.add(new TextConstructor(new VarRef("value", AtomicType.STRING, T1), T1));
    all.addAll(content);
    return new TargetDocument(
        "ns0",
        ORDER,
        "root",
        List.of(
            new TargetSlots(
                queue,
                new VarRef("root", AtomicType.INTEGER, T1),
                0,
                Long.MAX_VALUE,
                "slot",
                "value",
                new ElementConstructor("", ORDER_ID, all, T1),
                T1)));
  }

  /** The {@code xsl:for-each} of the target field over {@code queue}, as the export writes it. */
  private static String forEach(String queue) {
    return "<xsl:for-each select=\"po:slots(" + queue + ", $root, ())\">";
  }

  @Test
  void aLiftIsPoLiftOverItsQueuesAndAnInlineFunctionOfItsSlotVariables() {
    Lift one =
        new Lift(
            List.of(underRoot(ORDER_ID, "a1")),
            List.of("in1"),
            new FunCall(
                REPLACE_STRING,
                List.of(slotVar("in1"), new Literal("a", L1), new Literal("X", L1)),
                L1),
            L1);
    Lift two =
        new Lift(
            List.of(underRoot(ORDER_ID, "a1"), underRoot(NOTE, "a2")),
            List.of("in1", "in2"),
            slotVar("in2"),
            L1);
    TargetDocument overOne = orderIds(one, List.of());
    TargetDocument overTwo = orderIds(two, List.of());
    // witness: both are IR as the verifier accepts it
    IrVerifier.verify(overOne);
    IrVerifier.verify(overTwo);

    assertThat(XsltExporter.export(overOne))
        .contains(
            forEach(
                "po:lift(("
                    + ORDER_IDS
                    + "), function($in as xs:string*) as xs:string"
                    + " { let $in1 := $in[1] return po:replaceString($in1, 'a', 'X') })"));
    assertThat(XsltExporter.export(overTwo))
        .contains(
            forEach(
                "po:lift(("
                    + ORDER_IDS
                    + ", "
                    + NOTES
                    + "), function($in as xs:string*) as xs:string"
                    + " { let $in1 := $in[1], $in2 := $in[2] return $in2 })"));
  }

  @Test
  void aLiftAsAnElementsContentIsACompilerBug() {
    Lift lift = new Lift(List.of(underRoot(NOTE, "a2")), List.of("in1"), slotVar("in1"), L1);
    // witness: the same Lift as the target field's queue exports
    assertThat(XsltExporter.export(orderIds(lift, List.of()))).contains("po:lift(");

    assertThatThrownBy(
            () -> XsltExporter.export(orderIds(underRoot(ORDER_ID, "a1"), List.of(lift))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("l1");
  }
}
