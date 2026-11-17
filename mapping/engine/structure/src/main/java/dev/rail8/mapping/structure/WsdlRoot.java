package dev.rail8.mapping.structure;

/**
 * The element of a WSDL operation's input, output or fault.
 *
 * @param wsdl the operation and its direction
 */
public record WsdlRoot(WsdlOperation wsdl) implements RootSelector {}
