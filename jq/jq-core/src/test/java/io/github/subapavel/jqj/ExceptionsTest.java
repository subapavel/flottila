package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.JqTermination.Cause;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ADR-0016 and ADR-0024: the four JqException subclasses carry what they were given, and no stack trace. */
class ExceptionsTest {
  /**
   * ADR-0016: a budget for the BUDGET cause, null for every other one. Written out: the nine pairs a
   * JqTermination accepts; the other 21 of the 5 causes times 6 budget values (null included) are rejected.
   */
  private static final Set<String> ACCEPTED =
      Set.of(
          "BUDGET STEPS",
          "BUDGET DEADLINE",
          "BUDGET RECURSION_DEPTH",
          "BUDGET BYTES_BUILT",
          "BUDGET BYTES_WRITTEN",
          "HALT null",
          "STACK null",
          "STALE_VIEW null",
          "CANCELLED null");

  static Stream<Arguments> causesAndBudgets() {
    List<Budget> budgets = new ArrayList<>(Arrays.asList(Budget.values()));
    budgets.add(null);
    return Arrays.stream(Cause.values())
        .flatMap(
            cause ->
                budgets.stream()
                    .map(budget -> argumentSet(cause + " " + budget, cause, budget, ACCEPTED.contains(cause + " " + budget))));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("causesAndBudgets")
  void terminationCarriesBudgetExactlyWhenCauseIsBudget(Cause cause, Budget budget, boolean accepted) {
    if (accepted) {
      JqTermination termination = new JqTermination(cause, 5, JqNull.NULL, budget);
      assertThat(termination.cause()).isEqualTo(cause);
      assertThat(termination.budget()).isEqualTo(budget);
    } else {
      assertThatIllegalArgumentException().as("cause %s with budget %s", cause, budget).isThrownBy(() -> new JqTermination(cause, 5, JqNull.NULL, budget));
    }
  }

  @Test
  void terminationRejectsNullCauseOrValue() {
    // The approved listing: cause and value are non-null; "no value" is JqNull.NULL (ADR-0024).
    assertThatNullPointerException().as("null cause").isThrownBy(() -> new JqTermination(null, 0, JqNull.NULL, null));
    // With a budget only the null check gives the NPE: without it, the budget-cause pairing rejects the
    // null cause with an IllegalArgumentException.
    assertThatNullPointerException().as("null cause with a budget").isThrownBy(() -> new JqTermination(null, 0, JqNull.NULL, Budget.STEPS));
    assertThatNullPointerException().as("null value").isThrownBy(() -> new JqTermination(Cause.HALT, 0, null, null));
  }

  @Test
  void jqExceptionsHaveNoStackTrace() {
    // Witness: this JVM records stack traces, so an empty one below is the exception's own doing.
    assertThat(new RuntimeException("control").getStackTrace()).isNotEmpty();

    List<JqException> all =
        List.of(
            new JqCompileException("compile message", 1, 1),
            new JqSyntaxException("syntax message", 0, 1, 1),
            new JqFilterException(JqNull.NULL),
            new JqTermination(Cause.HALT, 0, JqNull.NULL, null),
            new JqTermination(Cause.BUDGET, 5, JqNull.NULL, Budget.STEPS));
    for (JqException e : all) {
      assertThat(e.getStackTrace()).as(e.getClass().getSimpleName()).isEmpty();
    }
  }

  @Test
  void accessorsReturnConstructorValues() {
    JqCompileException compile = new JqCompileException("compile message", 3, 7);
    assertThat(compile.getMessage()).isEqualTo("compile message");
    assertThat(compile.line()).isEqualTo(3);
    assertThat(compile.column()).isEqualTo(7);
    assertThat(compile.blocks()).containsExactly("compile message");
    assertThatThrownBy(() -> compile.blocks().add("another block")).isInstanceOf(UnsupportedOperationException.class);

    // An offset past 2^31: a document may be longer than an int can count.
    JqSyntaxException syntax = new JqSyntaxException("syntax message", 5_000_000_000L, 2, 9);
    assertThat(syntax.getMessage()).isEqualTo("syntax message");
    assertThat(syntax.offset()).isEqualTo(5_000_000_000L);
    assertThat(syntax.line()).isEqualTo(2);
    assertThat(syntax.column()).isEqualTo(9);

    assertThat(new JqFilterException(JqBool.TRUE).value()).isSameAs(JqBool.TRUE);

    JqTermination halt = new JqTermination(Cause.HALT, 44, JqBool.FALSE, null);
    assertThat(halt.cause()).isEqualTo(Cause.HALT);
    assertThat(halt.exitCode()).isEqualTo(44);
    assertThat(halt.value()).isSameAs(JqBool.FALSE);
    assertThat(halt.budget()).isNull();

    JqTermination deadline = new JqTermination(Cause.BUDGET, 5, JqNull.NULL, Budget.DEADLINE);
    assertThat(deadline.cause()).isEqualTo(Cause.BUDGET);
    assertThat(deadline.exitCode()).isEqualTo(5);
    assertThat(deadline.value()).isSameAs(JqNull.NULL);
    assertThat(deadline.budget()).isEqualTo(Budget.DEADLINE);
  }
}
