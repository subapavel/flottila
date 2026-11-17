package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A run's parameters at the Executable seam (ADR-0038): one value per parameter slot of the tree, in their order; any
 * other number is an IllegalArgumentException before anything runs, the form without parameters included.
 */
class ParameterSlotsTest {
  private static final Source AT = new Source(0, 1);

  @Test
  void aRunGivesOneValuePerParameterSlotOrNothingRuns() {
    Executable second = Compiler.compile(new Var(AT, 1), 2, CompileMode.OPTIMISED);
    List<Object> emitted = new ArrayList<>();
    assertThatIllegalArgumentException().as("run with one value for two parameter slots").isThrownBy(() -> second.run(new RunState(), new Object[1], new Object(), emitted::add));
    assertThatIllegalArgumentException().as("run with three values for two parameter slots").isThrownBy(() -> second.run(new RunState(), new Object[3], new Object(), emitted::add));
    assertThatIllegalArgumentException().as("run without parameters of a tree with two").isThrownBy(() -> second.run(new RunState(), new Object(), emitted::add));
    assertThat(emitted).as("results of the refused runs").isEmpty();

    // Witness: two values run, and the Var of slot 1 reads the second.
    Object b = new Object();
    assertThat(second.run(new RunState(), new Object[] {new Object(), b}, new Object(), emitted::add)).as("run's answer with two values").isTrue();
    assertThat(emitted).as("results of $1 with two values").singleElement().isSameAs(b);
  }
}
