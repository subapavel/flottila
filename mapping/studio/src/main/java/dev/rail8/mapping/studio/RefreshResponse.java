package dev.rail8.mapping.studio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A refreshed working copy.
 *
 * @param model the working copy in canonical form with each side's fingerprint re-derived, except
 *     that of a side whose bundle fails to load; nothing else changes and nothing is saved
 * @param diagnostics the load errors of the sides that fail to load; when both load, the orphaned
 *     references with every other stage-3 diagnostic
 */
record RefreshResponse(JsonNode model, List<DiagnosticDto> diagnostics) {}
