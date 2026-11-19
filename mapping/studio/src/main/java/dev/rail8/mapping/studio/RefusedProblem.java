package dev.rail8.mapping.studio;

import java.util.List;

/**
 * The {@code model-refused} problem: a {@link Problem} with the diagnostics that refuse the model.
 *
 * @param type {@code urn:rail8:mapping:problem:model-refused}
 * @param title the slug's fixed summary
 * @param status 422
 * @param detail what was refused
 * @param diagnostics the refusing stage's diagnostics; empty when the stored file is no JSON
 */
record RefusedProblem(
    String type, String title, int status, String detail, List<DiagnosticDto> diagnostics) {}
