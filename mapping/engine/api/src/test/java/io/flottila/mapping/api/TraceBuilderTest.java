package io.flottila.mapping.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.runtime.Tracer;
import io.flottila.mapping.structure.Occurrence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A generating block's copies in the trace (#217, F-46): a copy whose block's first slot is
 * SUPPRESS creates nothing and keeps its slot number {@code (p - 1) * copies + j}, so the trace
 * shows it as suppressed, as a SUPPRESS slot of a queue is (ADR-0006, GLOSSARY "SUPPRESS").
 */
class TraceBuilderTest {

  private static final AuthoringId T2 = new AuthoringId("T2");

  /** T2 is 2..3, so a generating function writes two copies per parent occurrence. */
  private static final Map<AuthoringId, Occurrence> FIELDS = Map.of(T2, new Occurrence(2, 3));

  private static Trace.ParentSlot parent(long parentSlot, long created, Trace.Slot... slots) {
    return new Trace.ParentSlot(parentSlot, created, List.of(slots));
  }

  @Test
  void aSuppressedRepeatTracesItsCopiesAsSuppressed() {
    Trace trace =
        TraceBuilder.build(
            List.of(new Tracer.Repeat(T2, 1, 2, true), new Tracer.Repeat(T2, 2, 2, true)),
            FIELDS,
            100);

    assertThat(trace.entries().keySet()).containsExactly(T2);
    assertThat(trace.entries().get(T2))
        .isEqualTo(
            new Trace.Occurrences(
                2,
                3,
                List.of(
                    parent(
                        1,
                        0,
                        new Trace.Slot(1, Trace.SlotStatus.SUPPRESSED),
                        new Trace.Slot(2, Trace.SlotStatus.SUPPRESSED)),
                    parent(
                        2,
                        0,
                        new Trace.Slot(3, Trace.SlotStatus.SUPPRESSED),
                        new Trace.Slot(4, Trace.SlotStatus.SUPPRESSED))),
                false));

    // witness: the same copies, not suppressed, are all created
    Trace written =
        TraceBuilder.build(
            List.of(new Tracer.Repeat(T2, 1, 2, false), new Tracer.Repeat(T2, 2, 2, false)),
            FIELDS,
            100);
    assertThat(written.entries().get(T2))
        .isEqualTo(
            new Trace.Occurrences(
                2,
                3,
                List.of(
                    parent(
                        1,
                        2,
                        new Trace.Slot(1, Trace.SlotStatus.CREATED),
                        new Trace.Slot(2, Trace.SlotStatus.CREATED)),
                    parent(
                        2,
                        2,
                        new Trace.Slot(3, Trace.SlotStatus.CREATED),
                        new Trace.Slot(4, Trace.SlotStatus.CREATED))),
                false));
  }
}
