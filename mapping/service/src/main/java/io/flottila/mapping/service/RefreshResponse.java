package io.flottila.mapping.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A refreshed working copy (ADR-0015, ADR-0009 as amended on 2026-11-05).
 *
 * @param model the working copy in canonical form with each side's fingerprint re-derived, except
 *     that of a side whose bundle fails to load; nothing else changes and nothing is saved
 * @param diagnostics the load errors of the sides that fail to load; when both load, the orphaned
 *     references with every other stage-3 diagnostic
 */
public record RefreshResponse(
    @Schema(required = true, ref = MappingsResource.MODEL) JsonNode model,
    @Schema(required = true) List<DiagnosticDto> diagnostics) {}
