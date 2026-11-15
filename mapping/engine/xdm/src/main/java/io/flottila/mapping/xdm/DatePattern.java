package io.flottila.mapping.xdm;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A {@code java.text.SimpleDateFormat} pattern, a box parameter, compiled to the XPath picture of
 * {@code format-dateTime} that writes a date as SimpleDateFormat does in the English locale
 * (ADR-0003). {@link FormatDate} formats and parses with the picture; the helper module's {@code
 * po:date-picture} writes the same picture, and its {@code po:parse-date} parses with it. A pattern
 * whose output no picture gives, or that no strict parse reads back, is refused.
 *
 * <p>The pattern letters translated are {@code yyyy}, {@code M}, {@code MM}, {@code d}, {@code dd},
 * {@code H}, {@code HH}, {@code m}, {@code mm}, {@code s}, {@code ss}, {@code SSS}, {@code Z} and
 * {@code XXX}. Quoted text and every character that is no ASCII letter are literal text. Each field
 * stands at most once, and a field of one letter, whose digits vary in number, is never followed
 * directly by another number, so that a parse reads each field's digits in one way only.
 */
public final class DatePattern {

  /** The XPath component of each pattern letter by its count; a count not listed is refused. */
  private static final Map<Character, Map<Integer, String>> COMPONENTS =
      Map.of(
          'y', Map.of(4, "[Y0001]"),
          'M', Map.of(1, "[M1]", 2, "[M01]"),
          'd', Map.of(1, "[D1]", 2, "[D01]"),
          'H', Map.of(1, "[H1]", 2, "[H01]"),
          'm', Map.of(1, "[m1]", 2, "[m01]"),
          's', Map.of(1, "[s1]", 2, "[s01]"),
          'S', Map.of(3, "[f001]"),
          'Z', Map.of(1, "[Z0000]"),
          'X', Map.of(3, "[Z01:01t]"));

  private DatePattern() {}

  /**
   * The XPath picture that writes a date as {@code pattern} does.
   *
   * @throws IllegalArgumentException when {@code pattern} is no SimpleDateFormat pattern, or no
   *     picture writes and reads as it does: the message names the element
   */
  public static String picture(String pattern) {
    if (pattern.isEmpty()) {
      throw refused(pattern, "it is empty");
    }
    StringBuilder picture = new StringBuilder();
    Set<Character> fields = new HashSet<>();
    // the letter of the field just written when it is of one letter, else 0
    char variable = 0;
    for (JavaPattern.Run run : JavaPattern.runs(pattern)) {
      if (run.quoted()) {
        picture.append(literal(run.text()));
        variable = 0;
        continue;
      }
      String text = run.text();
      for (int i = 0; i < text.length(); ) {
        char c = text.charAt(i);
        int end = i;
        while (end < text.length() && text.charAt(end) == c) {
          end++;
        }
        if (!isLetter(c)) {
          picture.append(literal(text.substring(i, end)));
          variable = 0;
        } else {
          String component = component(pattern, c, end - i);
          boolean zone = c == 'Z' || c == 'X';
          if (variable != 0 && !zone) {
            throw refused(
                pattern,
                "the field "
                    + variable
                    + " of one letter is followed directly by the number "
                    + c
                    + ", so its digits cannot be told apart");
          }
          if (!fields.add(zone ? 'Z' : c)) {
            throw refused(pattern, "the field " + c + " stands twice");
          }
          picture.append(component);
          variable = end - i == 1 && !zone ? c : 0;
        }
        i = end;
      }
    }
    return picture.toString();
  }

  /** The component of {@code count} letters {@code c}, or refused. */
  private static String component(String pattern, char c, int count) {
    Map<Integer, String> counts = COMPONENTS.get(c);
    if (counts == null) {
      throw refused(pattern, "the pattern letter " + c);
    }
    String component = counts.get(count);
    if (component == null) {
      throw refused(pattern, "the field " + String.valueOf(c).repeat(count) + " of that length");
    }
    return component;
  }

  /** Whether SimpleDateFormat reads {@code c} unquoted as a pattern letter, or refuses it. */
  private static boolean isLetter(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  /** {@code text} as literal picture text: each square bracket doubled. */
  private static String literal(String text) {
    return text.replace("[", "[[").replace("]", "]]");
  }

  private static IllegalArgumentException refused(String pattern, String element) {
    return new IllegalArgumentException(
        "the date pattern \"" + pattern + "\" has no XPath picture: " + element);
  }
}
