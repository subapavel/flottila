package dev.rail8.mapping.studio;

import dev.rail8.mapping.structure.Refusal;

/**
 * Why a mapping may not use a refused field, on the wire: the structure's refusal.
 *
 * @param code the reason's code, the diagnostic code a mapping that uses the field is refused with,
 *     for example {@code ambiguous-field-path}
 * @param side {@code target} when the reason holds in a target only and a source reads the field,
 *     else {@code both}
 * @param file the schema file of the declaration that causes it, as the bundle names it
 * @param line the line of that declaration, where its start tag ends
 * @param column the column of that declaration, where its start tag ends
 */
record RefusalDto(String code, String side, String file, int line, int column) {

  static RefusalDto of(Refusal refusal) {
    return new RefusalDto(
        refusal.reason().code(),
        refusal.reason().targetOnly() ? "target" : "both",
        refusal.file(),
        refusal.line(),
        refusal.column());
  }
}
