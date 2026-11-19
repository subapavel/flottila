package dev.rail8.mapping.studio;

import dev.rail8.mapping.api.Trace;
import java.util.Locale;

/**
 * One slot of a parent slot's context in a test run's trace, on the wire.
 *
 * @param slot its slot number, counted across all contexts of the queue
 * @param status {@code created}, {@code suppressed} or {@code truncated} (cut by {@code maxOccurs})
 */
record SlotStatusDto(long slot, String status) {

  static SlotStatusDto of(Trace.Slot slot) {
    return new SlotStatusDto(slot.slot(), slot.status().name().toLowerCase(Locale.ROOT));
  }
}
