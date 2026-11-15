package io.flottila.mapping.ir;

import static io.flottila.mapping.ir.FieldLinkIr.A1;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER_ID;
import static io.flottila.mapping.ir.FieldLinkIr.T1;
import static io.flottila.mapping.ir.FieldLinkIr.document;
import static io.flottila.mapping.ir.FieldLinkIr.fieldLink;
import static io.flottila.mapping.ir.FieldLinkIr.queue;
import static io.flottila.mapping.ir.FieldLinkIr.rootSlot;
import static io.flottila.mapping.ir.FieldLinkIr.slots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The optimiser of #26 has zero passes and verifies again (ADR-0006): the optimised variant equals
 * the unoptimised one, and IR the verifier refuses is refused by the optimiser too.
 */
class IrOptimiserTest {

  @Test
  void optimiseWithZeroPassesKeepsTheDocument() {
    TargetDocument document = fieldLink();

    assertThat(IrOptimiser.optimise(document)).isEqualTo(fieldLink());
  }

  @Test
  void optimiseVerifiesAgain() {
    TargetDocument minAboveMax =
        document(
            slots(
                ORDER_ID,
                queue(ORDER_ID, 1, A1),
                rootSlot(),
                2,
                1,
                "v",
                new VarRef("v", AtomicType.STRING, T1),
                T1));

    assertThatThrownBy(() -> IrOptimiser.optimise(minAboveMax))
        .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
  }
}
