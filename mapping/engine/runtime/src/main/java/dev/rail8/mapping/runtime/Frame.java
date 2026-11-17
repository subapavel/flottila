package dev.rail8.mapping.runtime;

import dev.rail8.mapping.serializer.XmlSerializer;
import dev.rail8.mapping.xdm.QueueBlock;
import java.time.OffsetDateTime;

/**
 * The dynamic context of one mapping run: the projected source queues, the variables as slots, the
 * output, and the run's time. The compiled nodes find everything here by index, never by name.
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
   * The block of each {@code TargetSlots}' queue and of each generating block once it is evaluated,
   * else null.
   */
  final QueueBlock[] targetQueues;

  /**
   * The time the run started at, fixed for the whole run: its offset is the run's time zone, the
   * zone a date without an offset is read in and every date is written in.
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
