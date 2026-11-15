package io.flottila.mapping.ir;

import static io.flottila.mapping.ir.FieldLinkIr.A1;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER_ID;
import static io.flottila.mapping.ir.FieldLinkIr.T1;
import static io.flottila.mapping.ir.FieldLinkIr.document;
import static io.flottila.mapping.ir.FieldLinkIr.queue;
import static io.flottila.mapping.ir.FieldLinkIr.rootSlot;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A {@code Lift}'s slot variables are distinct, and none is {@code in}, the parameter of the
 * export's inline function (ADR-0025): a duplicate binds one name to two inputs, and {@code in}
 * would shadow {@code $in}, so the export would read another value than the interpreter.
 */
class LiftSlotVariablesTest {

  private static final AuthoringId L1 = new AuthoringId("l1");

  /** The field link with its queue the Lift over two copies of OrderId, its body {@code read}. */
  private static TargetDocument lift(List<String> slotVars, String read) {
    Lift lift =
        new Lift(
            List.of(queue(ORDER_ID, 1, A1), queue(ORDER_ID, 1, A1)),
            slotVars,
            new VarRef(read, AtomicType.STRING, L1),
            L1);
    return document(
        new TargetSlots(
            lift,
            rootSlot(),
            1,
            1,
            "slot",
            "v",
            new ElementConstructor(
                "",
                ORDER_ID,
                List.of(new TextConstructor(new VarRef("v", AtomicType.STRING, T1), T1)),
                T1),
            T1));
  }

  @Test
  void verifierRefusesASlotVariableBoundTwice() {
    // witness: two distinct slot variables are accepted
    assertThatCode(() -> IrVerifier.verify(lift(List.of("in1", "in2"), "in2")))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> IrVerifier.verify(lift(List.of("in1", "in1"), "in1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("l1")
        .hasMessageContaining("twice");
  }

  @Test
  void verifierRefusesTheSlotVariableIn() {
    // witness: a name that only starts with in is accepted
    assertThatCode(() -> IrVerifier.verify(lift(List.of("inn", "in2"), "inn")))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> IrVerifier.verify(lift(List.of("in1", "in"), "in")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("l1")
        .hasMessageContaining("reserved");
  }
}
