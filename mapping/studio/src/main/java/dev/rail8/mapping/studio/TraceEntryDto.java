package dev.rail8.mapping.studio;

import dev.rail8.mapping.api.Trace;
import java.util.LinkedHashMap;
import java.util.Map;

/** One entry of a test run's trace, on the wire: a union on {@code kind}. */
sealed interface TraceEntryDto permits TraceQueueDto, TraceOccurrencesDto, TraceAtomicDto {

  static TraceEntryDto of(Trace.Entry entry) {
    return switch (entry) {
      case Trace.Queue queue -> TraceQueueDto.of(queue);
      case Trace.Occurrences occurrences -> TraceOccurrencesDto.of(occurrences);
      case Trace.Atomic atomic -> TraceAtomicDto.of(atomic);
    };
  }

  /** The engine's trace on the wire: its entries by authoring id, in the engine's order. */
  static Map<String, TraceEntryDto> all(Trace trace) {
    Map<String, TraceEntryDto> entries = new LinkedHashMap<>();
    trace.entries().forEach((id, entry) -> entries.put(id.value(), of(entry)));
    return entries;
  }
}
