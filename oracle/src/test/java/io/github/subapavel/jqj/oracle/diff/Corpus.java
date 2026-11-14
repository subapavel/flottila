package io.github.subapavel.jqj.oracle.diff;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Where the differential corpus and the ratchet are, found from the class path so any working directory works. Public
 * for the compile-mode oracle, which runs the same cases.
 */
public final class Corpus {

  /** The one backend so far: the report's file suffix and the pass list's backend column (ADR-0020). */
  static final String BACKEND = "interpreter";

  private Corpus() {}

  /** {@code oracle/target/test-classes/diff}: the copied {@code oracle/src/test/resources/diff}. */
  public static Path casesDir() {
    URL url = Corpus.class.getResource("/diff");
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Every case of {@link #casesDir()}, sorted by id, read once per JVM: PIT reruns a factory for each of its dynamic
   * tests (F-84 (C)). Sound because PIT mutates no oracle class, so the loader is never a mutant (F-84 (B)).
   */
  public static List<DiffCorpus.Entry> cases() {
    return Cases.ALL;
  }

  /** The holder: its initializer reads the corpus on the first call of {@link #cases()}. */
  private static final class Cases {
    static final List<DiffCorpus.Entry> ALL = List.copyOf(DiffCorpus.load(casesDir()));
  }

  /** The repository root: four levels above {@code oracle/target/test-classes/diff}. */
  public static Path root() {
    return casesDir().getParent().getParent().getParent().getParent();
  }

  /** The deviation slugs of the repository's {@code DEVIATIONS.md}. */
  static Set<String> deviations() {
    return Deviations.slugs(root().resolve("DEVIATIONS.md"));
  }

  /** The ratcheted ids and the differential ids the ticket names with {@code -Doracle.require=<file>}. */
  static Set<String> required() {
    return required(System.getProperty("oracle.require"));
  }

  /** The ratcheted ids and the differential ids of {@code requireFile} (null: none). */
  static Set<String> required(String requireFile) {
    return DiffReport.required(root().resolve("ratchet/oracle/differential.txt"), requireFile, DiffReport.Oracle.DIFFERENTIAL);
  }

  /**
   * Writes rows into the report. Surefire sets {@code oracle.reports}; PIT does not, so a mutant's verdicts are never
   * written.
   */
  static void report(List<DiffReport.Row> rows) {
    String dir = System.getProperty("oracle.reports");
    if (dir != null) DiffReport.merge(Path.of(dir).resolve("differential-" + BACKEND + ".tsv"), rows);
  }
}
