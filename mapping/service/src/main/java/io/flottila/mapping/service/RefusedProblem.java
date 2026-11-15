package io.flottila.mapping.service;

import java.net.URI;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The {@code model-refused} problem (ADR-0024): a {@link Problem} with the diagnostics that refuse
 * the model.
 *
 * @param type {@code urn:flottila:mapping:problem:model-refused}
 * @param title the slug's fixed summary
 * @param status 422
 * @param detail what was refused
 * @param diagnostics the refusing stage's diagnostics; empty when the stored file is no JSON
 */
public record RefusedProblem(
    @Schema(required = true) URI type,
    @Schema(required = true) String title,
    @Schema(required = true) int status,
    @Schema(required = true) String detail,
    @Schema(required = true) List<DiagnosticDto> diagnostics) {}
