package dev.rail8.mapping.studio;

import dev.rail8.mapping.api.Trace;
import java.util.List;

/**
 * The queue a box gave in a test run, on the wire.
 *
 * @param kind {@value #KIND}
 * @param contexts the contexts kept, each an array of its values, {@code null} for SUPPRESS
 * @param capped whether the queue has more slots or contexts than the trace limit
 * @param slotCount the queue's slots, all of them
 * @param contextCount the queue's contexts, all of them
 */
record TraceQueueDto(
    String kind, List<List<String>> contexts, boolean capped, int slotCount, int contextCount)
    implements TraceEntryDto {

  static final String KIND = "queue";

  static TraceQueueDto of(Trace.Queue queue) {
    return new TraceQueueDto(
        KIND, queue.contexts(), queue.capped(), queue.slotCount(), queue.contextCount());
  }
}
