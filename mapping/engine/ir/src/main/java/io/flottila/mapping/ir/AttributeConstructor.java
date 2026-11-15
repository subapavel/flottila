package io.flottila.mapping.ir;

/**
 * An XML attribute of the output (ADR-0006 since #102), under the prefix lowering assigned
 * (ADR-0022). It stands only as the body of an attribute item: a {@link TargetSlots} with {@code
 * max <= 1} or a 1-copy {@link TargetRepeat}, among the first items of its element's content.
 *
 * @param prefix the prefix of {@code name}'s namespace; {@code ""} for a name without one
 * @param name the attribute's name
 * @param value the attribute's value, a {@code STRING}
 * @param origin the target field mapping
 */
public record AttributeConstructor(String prefix, Name name, Expr value, AuthoringId origin)
    implements Expr {}
