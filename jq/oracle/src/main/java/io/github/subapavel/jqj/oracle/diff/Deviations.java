package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deviation slugs that {@code DEVIATIONS.md} names (ADR-0020). A table row names its deviation case in its first
 * cell, {@code case}, with the case's {@code deviation <slug>} line in backticks: {@code `json/n_x` (`deviation nul`)}.
 * A slug quoted anywhere else, in prose or in another cell, names no row.
 */
public final class Deviations {

  /** The first cell of a table row; a {@code \|} inside it is part of the cell. */
  private static final Pattern FIRST_CELL = Pattern.compile("^\\|((?:[^|\\\\]|\\\\.)*)\\|");

  private static final Pattern SLUG = Pattern.compile("`deviation ([^`\\s]+)`");

  private Deviations() {}

  /** The slugs the rows of {@code file} name. */
  public static Set<String> slugs(Path file) {
    Set<String> slugs = new HashSet<>();
    try {
      for (String line : Files.readAllLines(file, UTF_8)) {
        Matcher cell = FIRST_CELL.matcher(line);
        if (!cell.find()) continue;
        Matcher slug = SLUG.matcher(cell.group(1));
        while (slug.find()) slugs.add(slug.group(1));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return slugs;
  }
}
