package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A structure to load.
 *
 * @param base the directory the bundle is relative to, which the caller resolves; the studio does
 *     not read it
 * @param bundle the schema files
 * @param root the root selector, as the mapping model spells it
 */
record StructureRequest(
    @JsonProperty(required = true) String base,
    @JsonProperty(required = true) List<String> bundle,
    @JsonProperty(required = true) JsonNode root) {}
