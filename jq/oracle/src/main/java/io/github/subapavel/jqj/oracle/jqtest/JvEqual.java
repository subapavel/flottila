package io.github.subapavel.jqj.oracle.jqtest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * jq's {@code jv_equal} as jq 1.8.2 with decNumber has it (ADR-0020), independent of jqj's {@code ==}:
 *
 * <ul>
 *   <li>kinds must match, and null, false and true are three kinds;
 *   <li>two literals compare as exact decimals; NaN is equal to nothing, and an infinity only to the same infinity;
 *   <li>any other number pair compares as doubles;
 *   <li>strings compare by UTF-8 bytes, arrays by position, objects by key set.
 * </ul>
 */
public final class JvEqual {

  private JvEqual() {}

  public static boolean equal(JvValue a, JvValue b) {
    return switch (a) {
      case JvValue.Constant c -> c == b;
      case JvValue.Num x -> b instanceof JvValue.Num y && numbers(x, y);
      case JvValue.Str x -> b instanceof JvValue.Str y && Arrays.equals(x.utf8(), y.utf8());
      case JvValue.Arr x -> b instanceof JvValue.Arr y && elements(x.elements(), y.elements());
      case JvValue.Obj x -> b instanceof JvValue.Obj y && members(x.members(), y.members());
    };
  }

  /** jv.c's {@code jvp_number_cmp(a, b) == 0}. */
  private static boolean numbers(JvValue.Num x, JvValue.Num y) {
    if (x.literal() && y.literal()) {
      if (x.decimal() != null && y.decimal() != null) return x.decimal().compareTo(y.decimal()) == 0;
      // decNumberCompare is zero for two equal infinities only: never with NaN, never finite against infinite.
      return x.decimal() == null && y.decimal() == null && x.value() == y.value();
    }
    return x.value() == y.value();
  }

  private static boolean elements(List<JvValue> x, List<JvValue> y) {
    if (x.size() != y.size()) return false;
    for (int i = 0; i < x.size(); i++) {
      if (!equal(x.get(i), y.get(i))) return false;
    }
    return true;
  }

  private static boolean members(Map<String, JvValue> x, Map<String, JvValue> y) {
    if (x.size() != y.size()) return false;
    for (Map.Entry<String, JvValue> e : x.entrySet()) {
      JvValue other = y.get(e.getKey());
      if (other == null || !equal(e.getValue(), other)) return false;
    }
    return true;
  }
}
