package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Binding errors in the operands the Binder walks as inner compile levels or inside a string (ADR-0040, #193): each
 * operand of {@code =} and {@code |=}, the path of an arithmetic update, the interpolation of a {@code ."..."} step
 * and of an object's string key. With no error around it, each is reported. Each expected message is jq-ref 1.8.2's
 * whole stderr for the filter with {@code -n}, rc 3, observed 2026-11-14.
 */
class OperandBindingErrorsTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '\'',
      textBlock =
          """
          $x = 1        => 1
          .a |= $x      => 7
          $x += 1       => 1
          ."\\($x)"     => 5
          {"\\($x)": 1} => 5
          """)
  void anUnboundVariableInTheOperandIsJqsError(String filter, int column) {
    JqCompileException e = assertThatExceptionOfType(JqCompileException.class).as("compile of %s", filter).isThrownBy(() -> ENGINE.compile(filter)).actual();
    String carets = " ".repeat(column - 1) + "^^";
    assertThat(e.getMessage())
        .as("message of %s", filter)
        .isEqualTo("jq: error: $x is not defined at <top-level>, line 1, column " + column + ":\n    " + filter + "\n    " + carets + "\njq: 1 compile error");
  }
}
