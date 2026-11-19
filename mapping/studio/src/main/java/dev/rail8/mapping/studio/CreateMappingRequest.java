package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * A new mapping: only its id is required; the author picks the structures later, on the Signature
 * tab. An optional member may be missing; {@code null} is read as missing.
 *
 * @param id the new mapping's id, which the caller places; the studio does not read it
 * @param description the mapping's description, at most 4,000 characters, or null
 * @param source the source structure, if chosen, else null
 * @param target the target structure, if chosen, else null
 */
record CreateMappingRequest(
    @JsonProperty(required = true) String id,
    @JsonSetter(nulls = Nulls.SET) String description,
    @JsonSetter(nulls = Nulls.SET) StructureChoice source,
    @JsonSetter(nulls = Nulls.SET) StructureChoice target) {}
