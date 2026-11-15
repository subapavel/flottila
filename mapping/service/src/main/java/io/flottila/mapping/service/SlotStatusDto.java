package io.flottila.mapping.service;

import io.flottila.mapping.api.Trace;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One slot of a parent slot's context in a test run's trace (ADR-0015 "Trace" since #53), on the
 * wire.
 *
 * @param slot its slot number, counted across all contexts of the queue
 * @param status {@code created}, {@code suppressed} or {@code truncated} (cut by {@code maxOccurs})
 */
public record SlotStatusDto(
    @Schema(required = true) long slot,
    @Schema(
            required = true,
            enumeration = {"created", "suppressed", "truncated"})
        String status) {

  static SlotStatusDto of(Trace.Slot slot) {
    return new SlotStatusDto(slot.slot(), slot.status().name().toLowerCase(Locale.ROOT));
  }
}
