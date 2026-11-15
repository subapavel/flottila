package io.flottila.mapping.service;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A compiled working copy's diagnostics (ADR-0015, ADR-0027).
 *
 * @param diagnostics those of the stage that refuses the working copy, with the {@code
 *     unreachable-box} warnings of one that passes stages 1 and 2; none when it compiles with every
 *     box reached
 */
public record CompileResponse(@Schema(required = true) List<DiagnosticDto> diagnostics) {}
