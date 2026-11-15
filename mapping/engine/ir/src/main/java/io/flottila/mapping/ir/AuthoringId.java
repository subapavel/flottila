package io.flottila.mapping.ir;

/**
 * The stable id of an authoring element (a target field mapping, a function box, a source-field
 * box) that a piece of compiled mapping came from (ADR-0006). Ids are unique within a mapping; the
 * mapping-model schema allows {@code ^[A-Za-z0-9_-]{1,64}$} (ADR-0010).
 *
 * @param value the id as the mapping model spells it
 */
public record AuthoringId(String value) {}
