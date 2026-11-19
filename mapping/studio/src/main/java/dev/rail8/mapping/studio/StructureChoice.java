package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One side of a new mapping: its schema bundle and root.
 *
 * @param bundle the schema files, relative to the mapping's directory
 * @param root the root selector, as the mapping model spells it
 */
record StructureChoice(
    @JsonProperty(required = true) List<String> bundle,
    @JsonProperty(required = true) JsonNode root) {}
