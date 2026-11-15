package io.flottila.mapping.structure;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The identity of a field (ADR-0007): the kind, namespace and local name of every step from the
 * root down to the field. Its text is EQNames joined by {@code /}, with {@code @} for attributes
 * and a bare name when there is no namespace, for example {@code /Q{urn:a}Order/Item/@id}. A JSON
 * path starts with the root {@code $}, and its property names percent-encode {@code % / @ { }}, for
 * example {@code /$/order/a%2Fb}.
 */
public record FieldPath(List<Step> steps) {

  public FieldPath {
    steps = List.copyOf(steps);
  }

  /** The path of a root field. */
  public static FieldPath of(Step root) {
    return new FieldPath(List.of(root));
  }

  /** The path of a child field of this one. */
  public FieldPath child(Step step) {
    List<Step> longer = new ArrayList<>(steps);
    longer.add(step);
    return new FieldPath(longer);
  }

  @Override
  public String toString() {
    return steps.stream().map(Step::toString).collect(Collectors.joining("/", "/", ""));
  }

  /**
   * One step of a field path. The namespace is {@code ""} when the name has none, and always for a
   * JSON property, whose local name is the property's name as the schema spells it.
   */
  public record Step(FieldKind kind, String namespace, String localName) {

    @Override
    public String toString() {
      String name = namespace.isEmpty() ? localName : "Q{" + namespace + "}" + localName;
      return switch (kind) {
        case ELEMENT -> name;
        case ATTRIBUTE -> "@" + name;
        case JSON_PROPERTY -> percentEncoded(localName);
      };
    }

    /**
     * {@code name} with each of {@code % / @ { }} as {@code %} and its two upper-case hex digits.
     */
    private static String percentEncoded(String name) {
      StringBuilder text = new StringBuilder();
      for (char c : name.toCharArray()) {
        if ("%/@{}".indexOf(c) >= 0) {
          text.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
        } else {
          text.append(c);
        }
      }
      return text.toString();
    }
  }
}
