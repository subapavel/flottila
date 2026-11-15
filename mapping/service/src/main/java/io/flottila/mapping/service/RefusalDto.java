package io.flottila.mapping.service;

import io.flottila.mapping.structure.Refusal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Why a mapping may not use a refused field, on the wire (ADR-0027 "Refused fields on the wire",
 * #116): the structure's refusal (ADR-0008, #92).
 *
 * @param code the reason's code, the diagnostic code a mapping that uses the field is refused with,
 *     for example {@code ambiguous-field-path}
 * @param side {@code target} when the reason holds in a target only and a source reads the field
 *     (#94, #95), else {@code both}
 * @param file the schema file of the declaration that causes it, as the bundle names it
 * @param line the line of that declaration, where its start tag ends
 * @param column the column of that declaration, where its start tag ends
 */
public record RefusalDto(
    @Schema(required = true) String code,
    @Schema(
            required = true,
            enumeration = {"both", "target"})
        String side,
    @Schema(required = true) String file,
    @Schema(required = true) int line,
    @Schema(required = true) int column) {

  static RefusalDto of(Refusal refusal) {
    return new RefusalDto(
        refusal.reason().code(),
        refusal.reason().targetOnly() ? "target" : "both",
        refusal.file(),
        refusal.line(),
        refusal.column());
  }
}
