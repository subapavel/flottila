package io.flottila.mapping.mapping.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Field paths as the mapping model spells them (ADR-0007): steps joined by {@code /}, where an
 * EQName's {@code Q{namespace}} may itself hold a {@code /}. ADR-0011 checks contexts on this text;
 * nothing here knows the structure.
 */
public final class FieldPathText {

  private FieldPathText() {}

  /**
   * Whether {@code context} names a proper ancestor of {@code field}: a shorter run of its steps.
   */
  static boolean isProperAncestor(String context, String field) {
    List<String> ancestor = steps(context);
    List<String> steps = steps(field);
    return ancestor.size() < steps.size() && steps.subList(0, ancestor.size()).equals(ancestor);
  }

  /**
   * Whether {@code context} names the parent of {@code field}, the context a box has by default.
   */
  static boolean isParent(String context, String field) {
    List<String> steps = steps(field);
    return steps.subList(0, steps.size() - 1).equals(steps(context));
  }

  /** The steps of a path; the leading {@code /} gives an empty first step. */
  public static List<String> steps(String path) {
    List<String> steps = new ArrayList<>();
    boolean inNamespace = false;
    int start = 0;
    for (int k = 0; k < path.length(); k++) {
      char c = path.charAt(k);
      if (c == '{' || c == '}') {
        inNamespace = c == '{';
      } else if (c == '/' && !inNamespace) {
        steps.add(path.substring(start, k));
        start = k + 1;
      }
    }
    steps.add(path.substring(start));
    return steps;
  }
}
