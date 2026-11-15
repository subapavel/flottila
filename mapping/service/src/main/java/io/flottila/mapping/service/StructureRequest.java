package io.flottila.mapping.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A structure to load (ADR-0015).
 *
 * @param base the directory the bundle is relative to; {@code ""} is the workspace root
 * @param bundle the schema files
 * @param root the root selector, as the mapping model spells it
 */
public record StructureRequest(
    @Schema(required = true) @JsonProperty(required = true) String base,
    @Schema(required = true) @JsonProperty(required = true) List<String> bundle,
    @Schema(required = true, ref = MappingsResource.ROOT_SELECTOR) @JsonProperty(required = true)
        JsonNode root) {}
