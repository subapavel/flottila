package io.github.subapavel.jqj.internal.op;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The binary operators' dispatch where no filter reaches it: each operator's one exhaustive switch over {@link Operands}
 * matches no pair with a missing operand, so it ends with a {@code MatchException} rather than a result; and a string
 * or array longer than an array holds ends the run as over its bytes-built budget before any byte is charged.
 */
class ArithmeticOperandsTest {
  private static final List<Operation> BINARY =
      List.of(Arithmetic.PLUS, Arithmetic.MINUS, Arithmetic.MULTIPLY, Arithmetic.DIVIDE, Arithmetic.MODULO);

  /** One value of each kind. */
  private static final List<JqValue> KINDS =
      List.of(
          JqNull.NULL,
          JqBool.TRUE,
          LiteralNumber.ofLexeme("1"),
          BuiltString.of("a"),
          new BuiltArray(new Object[0], 0),
          BuiltObject.ofPairs(new Object[0]));

  private static RunState running() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    return state;
  }

  @Test
  void aMissingOperandMatchesNoPair() {
    for (Operation op : BINARY) {
      Fn2 fn = (Fn2) op.form();
      RunState state = running();
      if (op == Arithmetic.PLUS) {
        // l + null is l for every l, a missing one too, and null + r is r.
        assertThat(fn.apply(state, JqNull.NULL, null, JqNull.NULL)).as("a missing operand + null").isNull();
        assertThat(fn.apply(state, JqNull.NULL, JqNull.NULL, null)).as("null + a missing operand").isNull();
      } else {
        assertThatExceptionOfType(MatchException.class)
            .as("%s without its left operand", op.name())
            .isThrownBy(() -> fn.apply(state, JqNull.NULL, null, JqNull.NULL));
      }
      for (JqValue left : KINDS) {
        if (op == Arithmetic.PLUS && left == JqNull.NULL) {
          continue;
        }
        assertThatExceptionOfType(MatchException.class)
            .as("%s of %s without its right operand", op.name(), left.toJson())
            .isThrownBy(() -> fn.apply(state, JqNull.NULL, left, null));
      }
    }
  }

  @Test
  void aValueLongerThanAnArrayHoldsIsOverTheBytesBuiltBudget() {
    RunState state = running();
    assertThatExceptionOfType(Termination.class)
        .as("a string past the longest array")
        .isThrownBy(() -> Built.string(state, Built.MAX_LENGTH + 1L))
        .extracting(Termination::budget)
        .isEqualTo(Termination.Budget.BYTES_BUILT);
    assertThatExceptionOfType(Termination.class)
        .as("an array past the longest array")
        .isThrownBy(() -> Built.elements(state, Built.MAX_LENGTH + 1L))
        .extracting(Termination::budget)
        .isEqualTo(Termination.Budget.BYTES_BUILT);
  }
}
