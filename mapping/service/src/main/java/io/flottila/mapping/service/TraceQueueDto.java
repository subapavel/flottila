package io.flottila.mapping.service;

import io.flottila.mapping.api.Trace;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The queue a box gave in a test run (ADR-0015 "Trace" since #53), on the wire.
 *
 * @param kind {@value #KIND}
 * @param contexts the contexts kept, each an array of its values, {@code null} for SUPPRESS
 * @param capped whether the queue has more slots or contexts than the trace limit
 * @param slotCount the queue's slots, all of them
 * @param contextCount the queue's contexts, all of them
 */
public record TraceQueueDto(
    @Schema(required = true, constValue = TraceQueueDto.KIND) String kind,
    @Schema(required = true, type = SchemaType.ARRAY, implementation = TraceQueueDto.Context.class)
        List<List<String>> contexts,
    @Schema(required = true) boolean capped,
    @Schema(required = true) int slotCount,
    @Schema(required = true) int contextCount)
    implements TraceEntryDto {

  static final String KIND = "queue";

  /**
   * The schema of a context: an array of values. smallrye-openapi gives an array's items no
   * nullable of their own, so the schema names a type for them (#53 O2); none is ever made.
   */
  @Schema(name = "TraceContext", type = SchemaType.ARRAY, implementation = Value.class)
  static final class Context {
    private Context() {}
  }

  /** The schema of a value: a string, or {@code null} for SUPPRESS; none is ever made. */
  @Schema(name = "TraceValue", type = SchemaType.STRING, nullable = true)
  static final class Value {
    private Value() {}
  }

  static TraceQueueDto of(Trace.Queue queue) {
    return new TraceQueueDto(
        KIND, queue.contexts(), queue.capped(), queue.slotCount(), queue.contextCount());
  }
}
