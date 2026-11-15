package io.flottila.mapping.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A test run of the editor's working copy (ADR-0015).
 *
 * @param model the working copy, unsaved
 * @param input the input message
 */
public record TestRequest(
    @Schema(required = true, ref = MappingsResource.MODEL) @JsonProperty(required = true)
        JsonNode model,
    @Schema(required = true) @JsonProperty(required = true) String input) {}
