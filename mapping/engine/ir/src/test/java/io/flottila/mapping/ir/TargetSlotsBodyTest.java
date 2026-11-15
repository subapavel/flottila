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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A target field's body is the content created per slot, so it is content, never a value (#51
 * design: the verifier checks that a position that takes content gets content).
 */
class TargetSlotsBodyTest {

  /** The field link {@code OrderId <- OrderId} with {@code body} per slot. */
  private static TargetDocument withBody(Expr body) {
    return document(
        new TargetSlots(queue(ORDER_ID, 1, A1), rootSlot(), 1, 1, "slot", "v", body, T1));
  }

  static Stream<Arguments> values() {
    return Stream.of(
        Arguments.of("the slot's value", new VarRef("v", AtomicType.STRING, T1)),
        Arguments.of("a literal", new Literal("a", T1)),
        Arguments.of("a source queue", queue(ORDER_ID, 1, A1)));
  }

  @ParameterizedTest(name = "{0} as a target field's body")
  @MethodSource("values")
  void verifierRefusesAValueAsATargetFieldsBody(String why, Expr value) {
    // witness: the same target field with an element as its body is accepted
    assertThatCode(
            () ->
                IrVerifier.verify(
                    withBody(
                        new ElementConstructor(
                            "",
                            ORDER_ID,
                            List.of(new TextConstructor(new Literal("a", T1), T1)),
                            T1))))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> IrVerifier.verify(withBody(value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("a value where content stands");
  }
}
