package dev.rail8.mapping.studio;

import dev.rail8.mapping.api.Trace;
import java.util.List;

/**
 * The slots of one parent slot's context in a test run's trace, on the wire. {@code created < min}
 * marks the parent slot that raised {@code po:MINOCCURS}.
 *
 * @param parentSlot the slot number of the parent occurrence
 * @param created how many occurrences the slots create
 * @param slots the slots kept, in order
 */
record ParentSlotDto(long parentSlot, long created, List<SlotStatusDto> slots) {

  static ParentSlotDto of(Trace.ParentSlot parent) {
    return new ParentSlotDto(
        parent.parentSlot(),
        parent.created(),
        parent.slots().stream().map(SlotStatusDto::of).toList());
  }
}
