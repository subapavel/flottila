package io.flottila.mapping.xdm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Java/SAP pattern of a box parameter (ADR-0003), {@code DecimalFormat} or {@code
 * SimpleDateFormat}, split into its runs: text in quotes, which both read as literal text, and the
 * unquoted pattern characters between. Two quotes are one quote, inside quotes and outside. Each
 * formatting function translates the runs into an XPath picture; the helper module's {@code
 * po:pattern-runs} splits a pattern the same way.
 */
public final class JavaPattern {

  /**
   * One run of a pattern.
   *
   * @param text the run's characters, a quoted run's without its quotes and with each {@code ''}
   *     read as {@code '}
   * @param quoted whether the run is literal text: quoted, or two quotes outside quotes
   */
  public record Run(String text, boolean quoted) {}

  /** Two quotes, one quote; or a quoted text, in which two quotes are one. */
  private static final Pattern QUOTED = Pattern.compile("''|'((?:[^']|'')*)'");

  private JavaPattern() {}

  /**
   * The runs of {@code pattern}, in pattern order.
   *
   * @throws IllegalArgumentException when a quote of {@code pattern} is never closed
   */
  public static List<Run> runs(String pattern) {
    List<Run> runs = new ArrayList<>();
    Matcher quoted = QUOTED.matcher(pattern);
    int end = 0;
    while (quoted.find()) {
      unquoted(pattern, pattern.substring(end, quoted.start()), runs);
      String text = quoted.group(1);
      runs.add(new Run(text == null ? "'" : text.replace("''", "'"), true));
      end = quoted.end();
    }
    unquoted(pattern, pattern.substring(end), runs);
    return List.copyOf(runs);
  }

  /** Adds {@code text}, unquoted characters of {@code pattern}, to {@code runs} unless empty. */
  private static void unquoted(String pattern, String text, List<Run> runs) {
    if (text.indexOf('\'') >= 0) {
      throw new IllegalArgumentException(
          "the pattern \"" + pattern + "\" has a quote that is never closed");
    }
    if (!text.isEmpty()) {
      runs.add(new Run(text, false));
    }
  }
}
