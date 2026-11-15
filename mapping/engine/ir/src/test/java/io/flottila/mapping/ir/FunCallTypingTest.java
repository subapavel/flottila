package io.flottila.mapping.ir;

import static io.flottila.mapping.ir.FieldLinkIr.A1;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER_ID;
import static io.flottila.mapping.ir.FieldLinkIr.T1;
import static io.flottila.mapping.ir.FieldLinkIr.document;
import static io.flottila.mapping.ir.FieldLinkIr.queue;
import static io.flottila.mapping.ir.FieldLinkIr.rootSlot;
import static io.flottila.mapping.ir.FieldLinkIr.slots;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where the IR verifier's typing of a {@link FunCall} stops (ADR-0006, ADR-0025): only a context
 * function, one whose result is a queue, takes its atomic arguments as params, which are literals.
 * A single-value function's atomic arguments are its inputs, as a {@code Lift} body applies it to
 * its slot variables. Content gives no value, so it is no argument and no queue.
 */
class FunCallTypingTest {

  private static final QueueType QUEUE = new QueueType();

  private static final FunctionRef TRIM =
      new FunctionRef("trim", List.of(AtomicType.STRING), AtomicType.STRING);

  /** {@code <OrderId>{text}</OrderId>} per slot of {@code queue}, its value bound to {@code v}. */
  private static TargetDocument orderIds(Expr queue, Expr text) {
    return document(slots(ORDER_ID, queue, rootSlot(), 1, 1, "v", text, T1));
  }

  private static VarRef value() {
    return new VarRef("v", AtomicType.STRING, T1);
  }

  /**
   * A single-value call over the slot's value variable is accepted as a text's value. It is typed
   * {@code STRING}, its function's result type: the same function over a literal, which needs no
   * scope, is refused as a parent slot and as a queue, each naming the type it needs. Over a
   * variable not in scope the call is refused, naming the variable.
   */
  @Test
  void aSingleValueCallOverAVariableInScopeIsAString() {
    Expr orderIds = queue(ORDER_ID, 1, A1);
    FunCall trim = new FunCall(TRIM, List.of(value()), T1);
    FunCall trimLiteral = new FunCall(TRIM, List.of(new Literal(" a ", T1)), T1);
    FunCall unbound = new FunCall(TRIM, List.of(new VarRef("w", AtomicType.STRING, T1)), T1);

    assertThatCode(() -> IrVerifier.verify(orderIds(orderIds, trim))).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                IrVerifier.verify(
                    document(slots(ORDER_ID, orderIds, trimLiteral, 1, 1, "v", value(), T1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("its parent slot is no INTEGER");
    assertThatThrownBy(() -> IrVerifier.verify(orderIds(trimLiteral, value())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("its queue is no " + QUEUE);
    assertThatThrownBy(() -> IrVerifier.verify(orderIds(orderIds, unbound)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("w is not in scope as STRING");
  }

  @Test
  void contentIsNoValue() {
    FunctionRef removeContexts = new FunctionRef("removeContexts", List.of(QUEUE), QUEUE);
    // a text that is well-formed where it stands, outside any slot's body
    TextConstructor text = new TextConstructor(new Literal("a", T1), T1);
    // witness: the same documents with a queue in the content's place are accepted
    assertThatCode(
            () ->
                IrVerifier.verify(
                    orderIds(
                        new FunCall(removeContexts, List.of(queue(ORDER_ID, 1, A1)), T1), value())))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> IrVerifier.verify(orderIds(text, value())))
        .as("content as a target field's queue")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                IrVerifier.verify(
                    orderIds(new FunCall(removeContexts, List.of(text), T1), value())))
        .as("content as an argument")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
