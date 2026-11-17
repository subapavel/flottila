package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The differential oracle report {@code oracle/target/oracle-reports/differential-<backend>.tsv} and the required
 * cases, as {@code scripts/ratchet.java} reads and writes them (ADR-0020). Report columns: {@code id}, verdict,
 * detail, {@code def}.
 */
public final class DiffReport {

  /**
   * One case's line of the report: {@code id}, verdict, detail, then {@code label} if there is one (the json
   * oracle's), then {@code def}.
   *
   * @param label null for the oracles that have none
   */
  public record Row(String id, Verdict verdict, String label, String def) {
    public Row(String id, Verdict verdict, String def) {
      this(id, verdict, null, def);
    }

    String line() {
      return id + "\t" + verdict.kind() + "\t" + verdict.detail() + "\t" + (label == null ? "" : label + "\t") + def;
    }
  }

  private DiffReport() {}

  /** Writes {@code rows} into the report: each replaces the line of its id, other lines stay; sorted by id. */
  public static void merge(Path report, List<Row> rows) {
    Map<String, String> byId = new LinkedHashMap<>();
    for (Row r : rows) byId.put(r.id(), r.line());
    mergeLines(report, byId);
  }

  /**
   * Writes report lines, keyed by their id (the first column), into a report of any oracle: each replaces the line of
   * its id, other lines stay; sorted by id.
   */
  public static void mergeLines(Path report, Map<String, String> byId) {
    try {
      Map<String, String> lines = new TreeMap<>();
      if (Files.exists(report)) {
        for (String l : Files.readAllLines(report, UTF_8)) lines.put(l.split("\t", 2)[0], l);
      }
      lines.putAll(byId);
      Files.createDirectories(report.getParent());
      StringBuilder b = new StringBuilder();
      for (String l : lines.values()) b.append(l).append('\n');
      Files.writeString(report, b, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The oracle a required id belongs to, read from the shape of the id (ADR-0020's case ids). A require file is shared
   * by the three oracles, so each takes from it only the ids of its own shape.
   */
  public enum Oracle {
    DIFFERENTIAL,
    JSON,
    JQTEST;

    /**
     * {@code <file>.test#…} is the jqtest oracle's, {@code json/…} the json oracle's, and every other id the
     * differential oracle's: an id of no oracle's shape is therefore reported once, as an unknown differential id.
     */
    public static Oracle of(String id) {
      // The five characters before the first '#' are ".test"; with no '#' the offset is negative and never matches.
      if (id.startsWith(".test", id.indexOf('#') - ".test".length())) return JQTEST;
      if (id.startsWith("json/")) return JSON;
      return DIFFERENTIAL;
    }

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The required case ids of {@code oracle}: the first column of its ratchet pass list, if it exists, and the ids of
   * the ticket's {@code -Doracle.require=<file>}, if given, that {@link Oracle#of} gives to {@code oracle}. Blank and
   * {@code #} lines are skipped.
   *
   * @throws UncheckedIOException when a named require file cannot be read
   */
  public static Set<String> required(Path ratchet, String requireFile, Oracle oracle) {
    Set<String> ids = new HashSet<>();
    if (Files.exists(ratchet)) ids(ratchet, ids);
    if (requireFile != null) {
      Set<String> named = new HashSet<>();
      ids(Path.of(requireFile), named);
      for (String id : named) if (Oracle.of(id) == oracle) ids.add(id);
    }
    return ids;
  }

  /**
   * Checks that every required id names a case of the oracle's corpus, so a typo, a renamed case or a missing
   * recording cannot pass by producing no test.
   *
   * @throws IllegalStateException listing every required id, sorted, that is not one of {@code caseIds}
   */
  public static void requireKnown(Oracle oracle, Set<String> required, Collection<String> caseIds) {
    Set<String> unknown = new TreeSet<>(required);
    unknown.removeAll(new HashSet<>(caseIds));
    if (!unknown.isEmpty()) {
      throw new IllegalStateException(oracle + " oracle: required ids that name no case of its corpus: " + unknown);
    }
  }

  private static void ids(Path file, Set<String> into) {
    try {
      for (String l : Files.readAllLines(file, UTF_8)) {
        if (l.isEmpty() || l.startsWith("#")) continue;
        into.add(l.split("\t", 2)[0]);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
