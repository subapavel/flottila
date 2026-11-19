package dev.rail8.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rail8.mapping.ir.AuthoringId;
import org.junit.jupiter.api.Test;

/** A tracer with a bound keeps that many reports and refuses the next one, which stops the run. */
class TracerTest {

  private static final AuthoringId BOX = new AuthoringId("b1");

  @Test
  void aBoundedTracerKeepsItsBoundOfReports() {
    Tracer tracer = new Tracer(3);

    tracer.atomic(BOX, "1");
    tracer.atomic(BOX, "2");
    tracer.repeat(BOX, 1, 1, false);

    assertThat(tracer.reports()).hasSize(3);
  }

  @Test
  void aReportPastTheBoundIsRefusedAndNotKept() {
    Tracer tracer = new Tracer(2);
    tracer.atomic(BOX, "1");
    tracer.atomic(BOX, "2");

    assertThatThrownBy(() -> tracer.atomic(BOX, "3"))
        .isInstanceOf(Tracer.TooManyReports.class)
        .hasMessage("the trace has more than 2 reports");
    assertThat(tracer.reports()).hasSize(2);
  }

  @Test
  void anUnboundedTracerKeepsEveryReport() {
    Tracer tracer = new Tracer();

    for (int i = 0; i < 1_000; i++) {
      tracer.atomic(BOX, Integer.toString(i));
    }

    assertThat(tracer.reports()).hasSize(1_000);
  }

  @Test
  void aBoundBelowOneIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Tracer(0))
        .withMessage("a tracer keeps at least 1 report, not 0");
  }
}
