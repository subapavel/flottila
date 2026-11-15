package io.flottila.mapping.xdm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@code java.text.DecimalFormat} pattern, a box parameter, compiled to the XPath picture of
 * {@code format-number} that formats every {@code xs:decimal} as DecimalFormat does with the
 * symbols {@code .} and {@code ,} (ADR-0003). {@link FormatNumber} formats with the picture, and
 * the export's {@code format-number} with the one the helper module's {@code po:number-picture}
 * writes, which compiles the same way. A pattern whose output no picture gives is refused.
 *
 * <p>DecimalFormat reads a pattern as a positive subpattern and an optional negative one, each a
 * prefix, the number's digits and separators, and a suffix. The picture keeps the prefixes and
 * suffixes as passive text and rewrites the digits:
 *
 * <ul>
 *   <li>the integer digits become as many {@code 0}s as DecimalFormat's minimum, at least one when
 *       the number has no {@code 0} at all but integer digits and fraction digits, as DecimalFormat
 *       then shows one; and, with a grouping separator, one separator after every group of the size
 *       DecimalFormat uses, the digits after the last separator, which XPath repeats;
 *   <li>the fraction digits stay as they are;
 *   <li>a negative subpattern takes the positive one's digits, as DecimalFormat ignores its own.
 * </ul>
 */
public final class NumberPattern {

  /** A character an XPath picture reads as a digit, separator or sign, never as passive text. */
  private static final Pattern ACTIVE = Pattern.compile("[0-9#.,;%‰]");

  /**
   * DecimalFormat's integer digits: {@code #}s, then {@code 0}s, one grouping separator between.
   */
  private static final Pattern INTEGER = Pattern.compile("(#(,?#)*(,?0(,?0)*)?|0(,?0)*)?");

  /** DecimalFormat's decimal separator and fraction digits, {@code 0}s and then {@code #}s. */
  private static final Pattern FRACTION = Pattern.compile("(\\.0*#*)?");

  /** A letter {@code e} that XPath 3.1 may read as the exponent separator, as Saxon does. */
  private static final Pattern EXPONENT = Pattern.compile("[0#.,%‰]e|e[0#.,%‰]");

  /** A subpattern as it is read: its prefix, digits and separators, and suffix. */
  private static final class Subpattern {
    final StringBuilder prefix = new StringBuilder();
    final StringBuilder number = new StringBuilder();
    final StringBuilder suffix = new StringBuilder();

    /** Whether the reading is in the digits: they have started, and the suffix has not. */
    boolean inNumber() {
      return !number.isEmpty() && suffix.isEmpty();
    }

    /** Literal text: the prefix's until the digits start, then the suffix's. */
    void literal(CharSequence text) {
      (number.isEmpty() ? prefix : suffix).append(text);
    }

    /** The percent and per mille signs of its prefix and suffix. */
    String marks() {
      return (prefix.toString() + suffix).replaceAll("[^%‰]", "");
    }
  }

  private NumberPattern() {}

  /**
   * The XPath picture that formats a number as {@code pattern} does.
   *
   * @throws IllegalArgumentException when {@code pattern} is no DecimalFormat pattern, or no
   *     picture formats as it does: the message names the element
   */
  public static String picture(String pattern) {
    if (pattern.isEmpty()) {
      throw refused(pattern, "it is empty");
    }
    List<Subpattern> subpatterns = new ArrayList<>(List.of(new Subpattern()));
    for (JavaPattern.Run run : JavaPattern.runs(pattern)) {
      if (run.quoted()) {
        if (ACTIVE.matcher(run.text()).find()) {
          throw refused(
              pattern,
              "the quoted text "
                  + run.text()
                  + " holds a character that an XPath picture reads as a digit, separator or sign");
        }
        subpatterns.getLast().literal(run.text());
      } else {
        for (char c : run.text().toCharArray()) {
          read(pattern, c, subpatterns);
        }
      }
    }
    Subpattern positive = subpatterns.getFirst();
    String digits = digits(pattern, positive.number.toString());
    String picture = positive.prefix + digits + positive.suffix;
    if (subpatterns.size() == 2) {
      Subpattern negative = subpatterns.getLast();
      digits(pattern, negative.number.toString());
      if (negative.prefix.toString().contentEquals(positive.prefix)
          && negative.suffix.toString().contentEquals(positive.suffix)) {
        throw refused(
            pattern,
            "a negative subpattern with the positive one's prefix and suffix, which DecimalFormat"
                + " ignores");
      }
      if (!negative.marks().equals(positive.marks())) {
        throw refused(pattern, "a percent or per mille sign in one subpattern only");
      }
      picture += ";" + negative.prefix + digits + negative.suffix;
    }
    if (positive.marks().length() > 1) {
      throw refused(pattern, "more than one percent or per mille sign");
    }
    if (EXPONENT.matcher(picture).find()) {
      throw refused(
          pattern,
          "the letter e next to the digits or a percent sign, which XPath reads as an"
              + " exponent");
    }
    return picture;
  }

  /** Reads {@code c}, an unquoted character of {@code pattern}, into the last subpattern. */
  private static void read(String pattern, char c, List<Subpattern> subpatterns) {
    Subpattern current = subpatterns.getLast();
    switch (c) {
      case '#', '0', ',', '.' -> {
        if (!current.suffix.isEmpty()) {
          throw refused(pattern, "the digit or separator " + c + " after the suffix began");
        }
        current.number.append(c);
      }
      case ';' -> {
        if (subpatterns.size() == 2) {
          throw refused(pattern, "more than two subpatterns");
        }
        subpatterns.add(new Subpattern());
      }
      case '¤' -> throw refused(pattern, "the currency sign ¤");
      case 'E' -> {
        if (current.inNumber()) {
          throw refused(pattern, "the exponent E");
        }
        current.literal("E");
      }
      default -> {
        if (Character.isDigit(c)) {
          throw refused(pattern, "the digit " + c + ", which an XPath picture reads as a digit");
        }
        current.literal(String.valueOf(c));
      }
    }
  }

  /**
   * The picture's digits for {@code number}, the digits and separators of a subpattern of {@code
   * pattern}.
   */
  private static String digits(String pattern, String number) {
    if (number.isEmpty()) {
      throw refused(pattern, "a subpattern has no digit");
    }
    int dot = number.indexOf('.');
    String integer = dot < 0 ? number : number.substring(0, dot);
    // empty, or the decimal separator and the fraction digits
    String fraction = number.substring(integer.length());
    if (!INTEGER.matcher(integer).matches()) {
      throw refused(
          pattern,
          "the integer digits "
              + integer
              + " are no #s and then 0s with single grouping separators between them");
    }
    if (!FRACTION.matcher(fraction).matches()) {
      throw refused(
          pattern, "the fraction digits " + fraction.substring(1) + " are no 0s and then #s");
    }
    if (fraction.equals(".")) {
      throw refused(
          pattern, "the decimal separator has no digit after it, so DecimalFormat always shows it");
    }
    int zeros = (int) integer.chars().filter(d -> d == '0').count();
    if (!number.contains("0") && dot > 0) {
      // DecimalFormat shows one integer digit, as for #.## (observed, #74)
      zeros = 1;
    }
    int grouping = integer.contains(",") ? integer.length() - integer.lastIndexOf(',') - 1 : 0;
    StringBuilder picture = new StringBuilder();
    for (int i = Math.max(zeros, grouping + 1); i >= 1; i--) {
      picture.append(i <= zeros ? '0' : '#');
      if (grouping > 0 && i > 1 && (i - 1) % grouping == 0) {
        picture.append(',');
      }
    }
    return picture.append(fraction).toString();
  }

  private static IllegalArgumentException refused(String pattern, String element) {
    return new IllegalArgumentException(
        "the number pattern \"" + pattern + "\" has no XPath picture: " + element);
  }
}
