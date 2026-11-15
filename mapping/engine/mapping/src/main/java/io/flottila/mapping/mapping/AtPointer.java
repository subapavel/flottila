package io.flottila.mapping.mapping;

/**
 * A place in the mapping model's document, as stage 1 reports it, and as stage 3 reports a target
 * field the model lacks a member for: {@code /target}, or a target field mapping's absent member
 * under {@code /targetFieldMappings} (#65).
 *
 * @param pointer the RFC 6901 JSON pointer; {@code ""} is the document itself
 */
public record AtPointer(String pointer) implements Location {}
