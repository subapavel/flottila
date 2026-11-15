package io.flottila.mapping.service;

import io.flottila.mapping.api.Trace;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The slots of one parent slot's context in a test run's trace (ADR-0015 "Trace" since #53), on the
 * wire. {@code created < min} marks the parent slot that raised {@code po:MINOCCURS}.
 *
 * @param parentSlot the slot number of the parent occurrence
 * @param created how many occurrences the slots create
 * @param slots the slots kept, in order
 */
public record ParentSlotDto(
    @Schema(required = true) long parentSlot,
    @Schema(required = true) long created,
    @Schema(required = true) List<SlotStatusDto> slots) {

  static ParentSlotDto of(Trace.ParentSlot parent) {
    return new ParentSlotDto(
        parent.parentSlot(),
        parent.created(),
        parent.slots().stream().map(SlotStatusDto::of).toList());
  }
}
