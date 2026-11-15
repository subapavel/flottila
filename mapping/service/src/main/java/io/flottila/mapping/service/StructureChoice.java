package io.flottila.mapping.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One side of a new mapping: its schema bundle and root (ADR-0009).
 *
 * @param bundle the schema files, relative to the mapping's directory
 * @param root the root selector, as the mapping model spells it
 */
public record StructureChoice(
    @Schema(required = true) @JsonProperty(required = true) List<String> bundle,
    @Schema(required = true, ref = MappingsResource.ROOT_SELECTOR) @JsonProperty(required = true)
        JsonNode root) {}
