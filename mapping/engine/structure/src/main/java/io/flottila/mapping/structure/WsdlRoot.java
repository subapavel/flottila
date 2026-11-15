package io.flottila.mapping.structure;

/**
 * The element of a WSDL operation's input, output or fault (ADR-0008).
 *
 * @param wsdl the operation and its direction
 */
public record WsdlRoot(WsdlOperation wsdl) implements RootSelector {}
