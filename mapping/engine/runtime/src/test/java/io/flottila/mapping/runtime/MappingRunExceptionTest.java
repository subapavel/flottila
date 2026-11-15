package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import org.junit.jupiter.api.Test;

/**
 * The one way the runtime raises a function's data error (ADR-0026, F-19): at the function box,
 * single-value or queue, its message the place {@code the function box <id>: } and then what the
 * function says, as the export's po:data-error words it.
 */
class MappingRunExceptionTest {

  @Test
  void aDataErrorIsRaisedAtItsBoxAndNamesIt() {
    NumberFormatException cause = new NumberFormatException("For input string: \"x\"");

    MappingRunException error =
        MappingRunException.dataError(
            "err:FORG0001", new AuthoringId("d7"), "divide: x is no number", cause);

    assertThat(error.code()).isEqualTo("err:FORG0001");
    assertThat(error.authoringId()).isEqualTo(new AuthoringId("d7"));
    assertThat(error).hasMessage("the function box d7: divide: x is no number");
    assertThat(error.getCause()).isSameAs(cause);
  }
}
