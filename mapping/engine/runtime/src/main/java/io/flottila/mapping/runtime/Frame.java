package io.flottila.mapping.runtime;

import io.flottila.mapping.serializer.XmlSerializer;
import io.flottila.mapping.xdm.QueueBlock;
import java.time.OffsetDateTime;

/**
 * The dynamic context of one mapping run (ADR-0021): the projected source queues, the variables as
 * slots, the output, and the run's time (#80). The compiled nodes find everything here by index,
 * never by name.
 */
final class Frame {

  /** The source queues by their index in the projection plan. */
  final QueueBlock[] queues;

  final Tracer tracer;
  final XmlSerializer out;

  /** The {@code INTEGER} variables (slot numbers) by slot. */
  final long[] integers;

  /** The {@code STRING} variables (slot values) by slot. */
  final String[] strings;

  /**
   * The block of each {@code TargetSlots}' queue and of each generating block (#217) once it is
   * evaluated, else null (ADR-0006).
   */
  final QueueBlock[] targetQueues;

  /**
   * The time the run started at, fixed for the whole run (#80): its offset is the run's time zone,
   * the zone a date without an offset is read in and every date is written in.
   */
  final OffsetDateTime now;

  Frame(
      QueueBlock[] queues,
      Tracer tracer,
      XmlSerializer out,
      int integerSlotCount,
      int stringSlotCount,
      int targetQueueCount,
      OffsetDateTime now) {
    this.queues = queues;
    this.tracer = tracer;
    this.out = out;
    this.integers = new long[integerSlotCount];
    this.strings = new String[stringSlotCount];
    this.targetQueues = new QueueBlock[targetQueueCount];
    this.now = now;
  }
}
