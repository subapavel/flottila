package io.flottila.mapping.service;

import io.flottila.mapping.api.Trace;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The single value a box gave in a test run (ADR-0015 {@code {atomic: {type, value}}} since #83),
 * on the wire.
 *
 * @param kind {@value #KIND}
 * @param type the value's atomic type by its XML Schema name (ADR-0004), such as {@code xs:string}
 * @param value the value
 */
public record TraceAtomicDto(
    @Schema(required = true, constValue = TraceAtomicDto.KIND) String kind,
    @Schema(required = true) String type,
    @Schema(required = true) String value)
    implements TraceEntryDto {

  static final String KIND = "atomic";

  static TraceAtomicDto of(Trace.Atomic atomic) {
    return new TraceAtomicDto(KIND, atomic.typeName(), atomic.value());
  }
}
