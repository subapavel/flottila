package io.flottila.mapping.mapping;

/**
 * A string box parameter, such as a date pattern or a constant's value.
 *
 * @param value the parameter's text, {@code ""} allowed
 */
public record Text(String value) implements ParamValue {}
