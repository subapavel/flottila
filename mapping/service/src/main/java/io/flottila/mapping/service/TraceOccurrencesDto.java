package io.flottila.mapping.service;

import io.flottila.mapping.api.Trace;
import io.flottila.mapping.structure.Occurrence;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The occurrences a target field mapping created in a test run, per parent slot (ADR-0015 "Trace"
 * since #53), on the wire. Its queue is the entry of its {@code result} box.
 *
 * @param kind {@value #KIND}
 * @param min the field's effective minimum occurrence
 * @param max the field's effective maximum occurrence; absent when it is unbounded
 * @param parents the parent records kept
 * @param capped whether the field has more parent records or slot records than the trace limit
 */
public record TraceOccurrencesDto(
    @Schema(required = true, constValue = TraceOccurrencesDto.KIND) String kind,
    @Schema(required = true) long min,
    @Schema(nullable = false) Optional<Long> max,
    @Schema(required = true) List<ParentSlotDto> parents,
    @Schema(required = true) boolean capped)
    implements TraceEntryDto {

  static final String KIND = "occurrences";

  static TraceOccurrencesDto of(Trace.Occurrences occurrences) {
    return new TraceOccurrencesDto(
        KIND,
        occurrences.min(),
        Optional.of(occurrences.max()).filter(max -> max != Occurrence.UNBOUNDED),
        occurrences.parents().stream().map(ParentSlotDto::of).toList(),
        occurrences.capped());
  }
}
