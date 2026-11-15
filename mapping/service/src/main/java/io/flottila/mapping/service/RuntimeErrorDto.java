package io.flottila.mapping.service;

import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A runtime error of a test run (ADR-0015), on the wire.
 *
 * @param code the error's QName, such as {@code po:MINOCCURS} or {@code flottila:TIMEOUT}
 * @param message what stopped the run, in English
 * @param authoringId where the error was raised (ADR-0015 since #52): the target field mapping for
 *     {@code po:MINOCCURS}, the function box for a context function's data error; absent for {@code
 *     flottila:TIMEOUT}, an absent member, never null
 */
public record RuntimeErrorDto(
    @Schema(required = true) String code,
    @Schema(required = true) String message,
    @Schema(nullable = false) Optional<String> authoringId) {}
