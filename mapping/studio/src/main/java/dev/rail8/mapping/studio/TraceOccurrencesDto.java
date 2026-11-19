package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.rail8.mapping.api.Trace;
import dev.rail8.mapping.structure.Occurrence;
import java.util.List;

/**
 * The occurrences a target field mapping created in a test run, per parent slot, on the wire. Its
 * queue is the entry of its {@code result} box.
 *
 * @param kind {@value #KIND}
 * @param min the field's effective minimum occurrence
 * @param max the field's effective maximum occurrence; null, so an absent member, when it is
 *     unbounded
 * @param parents the parent records kept
 * @param capped whether the field has more parent records or slot records than the trace limit
 */
record TraceOccurrencesDto(
    String kind,
    long min,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long max,
    List<ParentSlotDto> parents,
    boolean capped)
    implements TraceEntryDto {

  static final String KIND = "occurrences";

  static TraceOccurrencesDto of(Trace.Occurrences occurrences) {
    long max = occurrences.max();
    return new TraceOccurrencesDto(
        KIND,
        occurrences.min(),
        max == Occurrence.UNBOUNDED ? null : max,
        occurrences.parents().stream().map(ParentSlotDto::of).toList(),
        occurrences.capped());
  }
}
