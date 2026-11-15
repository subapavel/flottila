package io.flottila.mapping.structure;

/**
 * An XML root element.
 *
 * @param element the element's name as an EQName, {@code Q{urn:a}Order}, or a bare name when it has
 *     no namespace
 */
public record ElementRoot(String element) implements RootSelector {}
