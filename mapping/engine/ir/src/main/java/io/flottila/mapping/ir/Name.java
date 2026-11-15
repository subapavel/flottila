package io.flottila.mapping.ir;

/**
 * An element name in the IR: a namespace and a local name. A name without a namespace has {@code
 * ""}.
 *
 * @param namespace the namespace URI, or {@code ""}
 * @param localName the local name
 */
public record Name(String namespace, String localName) {}
