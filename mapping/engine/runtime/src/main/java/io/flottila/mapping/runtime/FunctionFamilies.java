package io.flottila.mapping.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The function families of the runtime (ADR-0025 "Families", F-15). Each family is one {@code
 * <Family>Family.java} of this package, whose package-private maps {@code QUEUE} and {@code VALUE}
 * take a catalogue name to its binder. The build lists the families in {@code FamilyIndex}, which
 * scripts/function-families.java writes into target/, so no hand-written file lists them.
 */
final class FunctionFamilies {

  private FunctionFamilies() {}

  /** The ids of the families the index lists. */
  static Set<String> ids() {
    return FamilyIndex.QUEUE.keySet();
  }

  /**
   * The functions of every family in {@code families}, by name.
   *
   * @param families each family's functions, by family id
   * @throws IllegalStateException when a name is in two families
   */
  static <V> Map<String, V> merge(Map<String, Map<String, V>> families) {
    Map<String, V> merged = new HashMap<>();
    Map<String, String> familyOf = new HashMap<>();
    // in id order, so that the message names the two families in the same order every time
    new TreeMap<>(families)
        .forEach(
            (family, functions) ->
                functions.forEach(
                    (name, binder) -> {
                      String other = familyOf.putIfAbsent(name, family);
                      if (other != null) {
                        throw new IllegalStateException(
                            "the function "
                                + name
                                + " is in two families: "
                                + other
                                + " and "
                                + family);
                      }
                      merged.put(name, binder);
                    }));
    return Map.copyOf(merged);
  }
}
