package dev.rail8.mapping.studio;

import java.util.List;

/**
 * A compiled working copy's diagnostics.
 *
 * @param diagnostics those of the stage that refuses the working copy, with the {@code
 *     unreachable-box} warnings of one that passes stages 1 and 2; none when it compiles with every
 *     box reached
 */
record CompileResponse(List<DiagnosticDto> diagnostics) {}
