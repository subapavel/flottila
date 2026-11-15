package io.flottila.mapping.ir;

import java.util.List;

/**
 * An XML element of the output with its content (ADR-0006), under the prefix lowering assigned
 * (ADR-0022).
 *
 * @param prefix the prefix of {@code name}'s namespace; {@code ""} for a name without one
 * @param name the element's name
 * @param content the element's content, in output order
 * @param origin the target field mapping
 */
public record ElementConstructor(String prefix, Name name, List<Expr> content, AuthoringId origin)
    implements Expr {

  public ElementConstructor {
    content = List.copyOf(content);
  }
}
