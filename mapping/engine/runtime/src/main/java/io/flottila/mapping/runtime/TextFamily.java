package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.XsDecimal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The function family {@code text} (ADR-0025 "Families", F-15), #76: the single-value functions on
 * strings. A string is counted, indexed and compared in code points, as XPath does and as the
 * export does; SAP PO counts UTF-16 units, so a character outside the BMP is a documented deviation
 * of every function whose result depends on the count or the order (ADR-0025). A truth value is
 * written {@code true} or {@code false}. {@code substring} reads its two params in the {@code
 * xs:decimal} lexical form, anything else is {@code err:FORG0001} at the function box (ADR-0025,
 * F-19). The binders are methods, not lambdas of the map, so that PIT mutates them.
 */
final class TextFamily {

  /** The family's queue functions: it has none. */
  static final Map<String, QueueLibrary.Binder> QUEUE = Map.of();

  /** The family's single-value functions. */
  static final Map<String, SingleValueLibrary.Binder> VALUE =
      Map.ofEntries(
          Map.entry("compare", TextFamily::compare),
          Map.entry("concat", TextFamily::concat),
          Map.entry("endsWith", TextFamily::endsWith),
          Map.entry("equalsS", TextFamily::equalsS),
          Map.entry("indexOf", TextFamily::indexOf),
          Map.entry("lastIndexOf", TextFamily::lastIndexOf),
          Map.entry("length", TextFamily::length),
          Map.entry("replaceString", TextFamily::replaceString),
          Map.entry("startsWith", TextFamily::startsWith),
          Map.entry("substring", TextFamily::substring),
          Map.entry("toLowerCase", TextFamily::toLowerCase),
          Map.entry("toUpperCase", TextFamily::toUpperCase),
          Map.entry("trim", TextFamily::trim));

  private TextFamily() {}

  /** {@code compare(a, b)}: -1, 0 or 1, the order of the two in code points. */
  static Node.OfString compare(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> Integer.toString(compareCodePoints(a.eval(frame), b.eval(frame)));
  }

  static Node.OfString concat(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> a.eval(frame) + b.eval(frame);
  }

  static Node.OfString endsWith(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString suffix = args.get(1);
    return frame -> Boolean.toString(value.eval(frame).endsWith(suffix.eval(frame)));
  }

  static Node.OfString equalsS(AuthoringId box, List<Node.OfString> args) {
    Node.OfString a = args.get(0);
    Node.OfString b = args.get(1);
    return frame -> Boolean.toString(a.eval(frame).equals(b.eval(frame)));
  }

  /** {@code indexOf(value, search)}: the 0-based code point index of the first match, or -1. */
  static Node.OfString indexOf(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString search = args.get(1);
    return frame -> {
      String text = value.eval(frame);
      return Integer.toString(codePointIndex(text, text.indexOf(search.eval(frame))));
    };
  }

  /** {@code lastIndexOf(value, search)}: the 0-based code point index of the last match, or -1. */
  static Node.OfString lastIndexOf(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString search = args.get(1);
    return frame -> {
      String text = value.eval(frame);
      return Integer.toString(codePointIndex(text, text.lastIndexOf(search.eval(frame))));
    };
  }

  static Node.OfString length(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame -> {
      String text = value.eval(frame);
      return Integer.toString(text.codePointCount(0, text.length()));
    };
  }

  /**
   * {@code replaceString(value; search, replacement)}: every match of {@code search}, taken
   * literally, replaced by {@code replacement}; an empty {@code search} leaves the value as it is.
   */
  static Node.OfString replaceString(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString search = args.get(1);
    Node.OfString replacement = args.get(2);
    return frame -> {
      String text = value.eval(frame);
      String target = search.eval(frame);
      return target.isEmpty() ? text : text.replace(target, replacement.eval(frame));
    };
  }

  static Node.OfString startsWith(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString prefix = args.get(1);
    return frame -> Boolean.toString(value.eval(frame).startsWith(prefix.eval(frame)));
  }

  /**
   * {@code substring(value; start, length)}: the code points from the 0-based position {@code
   * start} for {@code length}, each rounded half up and the range cut to the value, as XPath's
   * {@code substring($value, start + 1, length)}. A param that is no {@code xs:decimal} raises
   * {@code err:FORG0001} at {@code box}.
   */
  static Node.OfString substring(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.get(0);
    Node.OfString start = args.get(1);
    Node.OfString length = args.get(2);
    return frame -> {
      String text = value.eval(frame);
      String startText = start.eval(frame);
      String lengthText = length.eval(frame);
      Optional<BigDecimal> from = XsDecimal.parse(startText);
      Optional<BigDecimal> count = XsDecimal.parse(lengthText);
      if (from.isEmpty() || count.isEmpty()) {
        throw MappingRunException.dataError(
            RuntimeErrors.FORG0001,
            box,
            "substring: the parameters start \""
                + startText
                + "\" and length \""
                + lengthText
                + "\" are not both xs:decimal",
            null);
      }
      return substring(text, from.get(), count.get());
    };
  }

  static Node.OfString toLowerCase(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame -> value.eval(frame).toLowerCase(Locale.ROOT);
  }

  static Node.OfString toUpperCase(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame -> value.eval(frame).toUpperCase(Locale.ROOT);
  }

  /** {@code trim}: leaves out the XML whitespace (space, tab, line feed, return) at both ends. */
  static Node.OfString trim(AuthoringId box, List<Node.OfString> args) {
    Node.OfString value = args.getFirst();
    return frame -> strip(value.eval(frame));
  }

  /** {@code text} without the XML whitespace at both ends. */
  private static String strip(String text) {
    int from = 0;
    int to = text.length();
    while (from < to && isXmlWhitespace(text.charAt(from))) {
      from++;
    }
    while (to > from && isXmlWhitespace(text.charAt(to - 1))) {
      to--;
    }
    return text.substring(from, to);
  }

  private static boolean isXmlWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  /** The code point index of the UTF-16 index {@code at} of {@code text}, or -1 for -1. */
  private static int codePointIndex(String text, int at) {
    return at < 0 ? -1 : text.codePointCount(0, at);
  }

  /** -1, 0 or 1: {@code a} before, equal to or after {@code b} in code point order. */
  static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int x = a.codePointAt(i);
      int y = b.codePointAt(j);
      if (x != y) {
        return x < y ? -1 : 1;
      }
      i += Character.charCount(x);
      j += Character.charCount(y);
    }
    return Boolean.compare(i < a.length(), j < b.length());
  }

  private static String substring(String text, BigDecimal start, BigDecimal length) {
    int points = text.codePointCount(0, text.length());
    BigDecimal first = XsDecimal.round(start);
    BigDecimal from = first.max(BigDecimal.ZERO);
    BigDecimal to = first.add(XsDecimal.round(length)).min(BigDecimal.valueOf(points));
    if (from.compareTo(to) >= 0) {
      return "";
    }
    int begin = text.offsetByCodePoints(0, from.intValueExact());
    int end = text.offsetByCodePoints(begin, to.subtract(from).intValueExact());
    return text.substring(begin, end);
  }
}
