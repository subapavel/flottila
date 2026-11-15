package io.flottila.mapping.service;

import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The RFC 9457 body of a transport error (glossary "Problem"), sent as {@code
 * application/problem+json}.
 *
 * @param type {@code urn:flottila:mapping:problem:<slug>} (ADR-0024)
 * @param title the slug's fixed summary
 * @param status the HTTP status, fixed by the slug
 * @param detail what was wrong with this request
 */
public record Problem(
    @Schema(required = true) URI type,
    @Schema(required = true) String title,
    @Schema(required = true) int status,
    @Schema(required = true) String detail) {}
