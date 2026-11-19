package dev.rail8.mapping.studio;

import dev.rail8.mapping.api.Trace;

/**
 * The single value a box gave in a test run, on the wire.
 *
 * @param kind {@value #KIND}
 * @param type the value's atomic type by its XML Schema name, such as {@code xs:string}
 * @param value the value
 */
record TraceAtomicDto(String kind, String type, String value) implements TraceEntryDto {

  static final String KIND = "atomic";

  static TraceAtomicDto of(Trace.Atomic atomic) {
    return new TraceAtomicDto(KIND, atomic.typeName(), atomic.value());
  }
}
