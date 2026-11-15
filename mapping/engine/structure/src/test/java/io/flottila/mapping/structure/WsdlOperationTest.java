package io.flottila.mapping.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0008: a WSDL selector has a fault exactly when its direction is {@code fault} (#96). */
class WsdlOperationTest {

  @Test
  void an_input_or_output_has_no_fault_and_a_fault_direction_has_one() {
    assertThat(new WsdlOperation("Port", "Op", WsdlDirection.INPUT, Optional.empty()).fault())
        .isEmpty();
    assertThat(new WsdlOperation("Port", "Op", WsdlDirection.FAULT, Optional.of("F")).fault())
        .contains("F");
  }

  @Test
  void a_fault_beside_an_output_is_refused() {
    assertThatThrownBy(
            () -> new WsdlOperation("Port", "Op", WsdlDirection.OUTPUT, Optional.of("F")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a WSDL selector names a fault exactly when its direction is FAULT");
  }

  @Test
  void a_fault_direction_without_a_fault_is_refused() {
    assertThatThrownBy(() -> new WsdlOperation("Port", "Op", WsdlDirection.FAULT, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a WSDL selector names a fault exactly when its direction is FAULT");
  }
}
