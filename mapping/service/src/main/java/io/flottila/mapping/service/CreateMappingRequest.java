package io.flottila.mapping.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A new mapping (ADR-0015, ADR-0029): only its id is required; the author picks the structures
 * later, on the Signature tab. An optional member may be missing; {@code null} is read as missing.
 *
 * @param id the new mapping's id; its directory must exist
 * @param description the mapping's description, at most 4,000 characters
 * @param source the source structure, if chosen
 * @param target the target structure, if chosen
 */
public record CreateMappingRequest(
    @Schema(required = true) @JsonProperty(required = true) String id,
    @Schema(nullable = false) @JsonSetter(nulls = Nulls.AS_EMPTY) Optional<String> description,
    @Schema(nullable = false) @JsonSetter(nulls = Nulls.AS_EMPTY) Optional<StructureChoice> source,
    @Schema(nullable = false) @JsonSetter(nulls = Nulls.AS_EMPTY)
        Optional<StructureChoice> target) {}
