package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.TableText;
import java.util.List;
import java.util.Map;

/**
 * The function family {@code conversions} (ADR-0025 "Families", F-15), #83: each catalogue name to
 * its binder. {@code fixValues} decodes its table once, when it binds (ADR-0026 "Table params"),
 * and a key the table lacks gives, by {@code missing}, the key itself, {@code defaultValue}, or the
 * runtime error {@code po:FIXVALUES} at the function box. The binders are methods, not lambdas of
 * the maps, so that PIT mutates them.
 */
final class ConversionsFamily {

  /** The family's queue functions: none. */
  static final Map<String, QueueLibrary.Binder> QUEUE = Map.of();

  /** The family's single-value functions. */
  static final Map<String, SingleValueLibrary.Binder> VALUE =
      Map.ofEntries(Map.entry("fixValues", ConversionsFamily::fixValues));

  private ConversionsFamily() {}

  /**
   * {@code fixValues(key; table, missing, defaultValue)}: the table's value of the key, matched
   * exactly. Lowering gives every param as a {@code Literal}, so the table and {@code missing} are
   * read here, once (ADR-0026 "Table params").
   *
   * @throws IllegalArgumentException when the table or {@code missing} is no {@code Literal}, or
   *     {@code missing} is none of the catalogue's values: a compiler bug
   */
  static Node.OfString fixValues(AuthoringId box, List<Node.OfString> args) {
    Node.OfString key = args.get(0);
    Map<String, String> table = TableText.read(literal(box, args.get(1), "table"));
    String missing = literal(box, args.get(2), "missing");
    Node.OfString defaultValue = args.get(3);
    return switch (missing) {
      case "key" ->
          frame -> {
            String k = key.eval(frame);
            return table.getOrDefault(k, k);
          };
      case "default" ->
          frame -> {
            String value = table.get(key.eval(frame));
            return value != null ? value : defaultValue.eval(frame);
          };
      case "error" ->
          frame -> {
            String k = key.eval(frame);
            String value = table.get(k);
            if (value == null) {
              throw MappingRunException.dataError(
                  RuntimeErrors.FIXVALUES,
                  box,
                  "fixValues: the key \"" + k + "\" is not in the table",
                  null);
            }
            return value;
          };
      default -> throw new IllegalArgumentException("fixValues has no missing " + missing);
    };
  }

  /** The string of {@code arg}, the param {@code param} of {@code fixValues} at {@code box}. */
  private static String literal(AuthoringId box, Node.OfString arg, String param) {
    if (!(arg instanceof Node.Literal(String value))) {
      throw new IllegalArgumentException(
          "fixValues at " + box.value() + ": the param " + param + " is no literal");
    }
    return value;
  }
}
