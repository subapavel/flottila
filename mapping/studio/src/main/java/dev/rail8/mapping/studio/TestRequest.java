package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A test run of the editor's working copy.
 *
 * @param model the working copy, unsaved
 * @param input the input message
 */
record TestRequest(
    @JsonProperty(required = true) JsonNode model, @JsonProperty(required = true) String input) {}
